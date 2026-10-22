package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReceiveTransfer;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TransferReceived;
import lk.coopfed.knoweb.m5inventory.api.TransferShortfallDrafted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReceiveTransfer (25A section 6.3, "destination receive posts TRANSFER_IN with actuals"; doc 25
 * flow 6.6): the destination records the positive quantities that arrived.
 *
 * <p><b>Own-location writes (PR #148).</b> The destination's session receives, and writes only
 * the destination's rows: one {@code transfer_receipt} row and one actual-quantity {@code
 * transfer_receipt_line} per issued line at the destination, plus the TRANSFER_IN movements there.
 * The source's transfer and lines are read (the {@code dest_read} policy), never changed; the
 * status is RECEIVED because the receipt row exists.
 *
 * <p>Guards, in order: an OWN scope ({@code m5.scope.own_required}); the transfer visible to the
 * scope ({@code m5.transfer.not_found}); the scope at the destination or entity-wide
 * ({@code m5.transfer.not_destination}: a session at the source reads the transfer but does not
 * receive it); dispatched ({@code m5.transfer.not_dispatched}); not received before ({@code
 * m5.transfer.already_received}); and, when quantities are supplied, exactly one valid quantity
 * for every immutable transfer line. Every accepted quantity is positive and no greater than the
 * issued quantity.
 *
 * <p>Mutation: the receipt and its actual-quantity line facts; TRANSFER_IN per line at the frozen
 * TRANSFER_OUT cost. A positive difference creates an unposted, destination-owned shortfall draft;
 * it never posts another movement. Audit {@code TRANSFER_RECEIVED}, and
 * {@code TRANSFER_SELF_RECEIVED} (REVIEW) when the receiver issued it too (wave 2, M5-16: allowed,
 * flagged); event {@code transfer.received.v1}.
 */
@Service
@CommandHandler(permission = "shop.transfer.receive")
class ReceiveTransferHandler implements Handles<ReceiveTransfer, UUID> {

    static final String AUDIT_RECEIVED = "TRANSFER_RECEIVED";
    static final String AUDIT_SHORTFALL_DRAFTED = "TRANSFER_SHORTFALL_DRAFTED";
    static final String AUDIT_SELF_RECEIVED = "TRANSFER_SELF_RECEIVED";

    private final TransferStore store;
    private final TransferLifecycleLock lifecycleLock;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReceiveTransferHandler(
            TransferStore store,
            TransferLifecycleLock lifecycleLock,
            StockLedger ledger,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.lifecycleLock = lifecycleLock;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReceiveTransfer command, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
        lifecycleLock.acquire(command.transferId());
        TransferStore.Header transfer = store.find(command.transferId())
                .orElseThrow(() -> new ProblemException(
                        "m5.transfer.not_found", Map.of("transferId", String.valueOf(command.transferId()))));
        if (scope.locationId() != null && !scope.locationId().equals(transfer.toLocationId())) {
            throw new ProblemException("m5.transfer.not_destination");
        }
        if (!store.dispatched(transfer.transferId())) {
            throw new ProblemException("m5.transfer.not_dispatched");
        }
        if (store.received(transfer.transferId())) {
            throw new ProblemException("m5.transfer.already_received");
        }

        List<TransferStore.Line> lines = store.lines(transfer.transferId());
        Map<UUID, BigDecimal> receivedQuantities = receivedQuantities(command.lines(), lines);
        jdbc.update(
                "insert into inventory.transfer_receipt (transfer_id, owner_entity_id, location_id, from_location_id,"
                        + " received_by) values (?, ?, ?, ?, ?)",
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.toLocationId(),
                transfer.fromLocationId(),
                scope.userId());
        for (TransferStore.Line line : lines) {
            jdbc.update(
                    "insert into inventory.transfer_receipt_line"
                            + " (transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id,"
                            + " received_qty) values (?, ?, ?, ?, ?, ?)",
                    transfer.transferId(),
                    line.lineId(),
                    transfer.ownerEntityId(),
                    transfer.toLocationId(),
                    transfer.fromLocationId(),
                    receivedQuantities.get(line.lineId()));
        }
        List<TransferStore.Line> shortLines = lines.stream()
                .filter(line -> receivedQuantities.get(line.lineId()).compareTo(line.qty()) < 0)
                .toList();
        UUID shortfallId = null;
        BigDecimal shortfallQty = BigDecimal.ZERO.setScale(3);
        BigDecimal shortfallValue = BigDecimal.ZERO.setScale(4);
        if (!shortLines.isEmpty()) {
            shortfallId = Ids.next();
            jdbc.update(
                    "insert into inventory.transfer_shortfall"
                            + " (shortfall_id, transfer_id, owner_entity_id, location_id, from_location_id,"
                            + " category, status, created_by) values (?, ?, ?, ?, ?, 'DAMAGED_IN_TRANSIT', 'DRAFT', ?)",
                    shortfallId,
                    transfer.transferId(),
                    transfer.ownerEntityId(),
                    transfer.toLocationId(),
                    transfer.fromLocationId(),
                    scope.userId());
            for (TransferStore.Line line : shortLines) {
                BigDecimal missing = line.qty().subtract(receivedQuantities.get(line.lineId()));
                jdbc.update(
                        "insert into inventory.transfer_shortfall_line"
                                + " (shortfall_id, transfer_id, transfer_line_id, owner_entity_id, location_id,"
                                + " from_location_id, shortfall_qty, unit_cost) values (?, ?, ?, ?, ?, ?, ?, ?)",
                        shortfallId,
                        transfer.transferId(),
                        line.lineId(),
                        transfer.ownerEntityId(),
                        transfer.toLocationId(),
                        transfer.fromLocationId(),
                        missing,
                        line.unitCost());
                shortfallQty = shortfallQty.add(missing);
                shortfallValue = shortfallValue.add(missing.multiply(line.unitCost()));
            }
        }
        List<Movement> movements = new ArrayList<>();
        for (TransferStore.Line line : lines) {
            movements.add(new Movement(
                    transfer.toLocationId(),
                    line.batchId(),
                    LotCondition.GOOD,
                    MovementType.TRANSFER_IN,
                    receivedQuantities.get(line.lineId()),
                    line.unitCost(),
                    line.lineId()));
        }
        ledger.post(new PostMovements(transfer.transferId(), null, null, movements), scope);

        audit.record(
                AUDIT_RECEIVED,
                Subject.of("transfer", transfer.transferId()),
                Map.of("status", "IN_TRANSIT"),
                Map.of("status", "RECEIVED", "toLocationId", transfer.toLocationId(), "lines", lines.size()),
                scope);
        if (shortfallId != null) {
            audit.record(
                    AUDIT_SHORTFALL_DRAFTED,
                    Subject.of("transfer_shortfall", shortfallId),
                    null,
                    Map.of(
                            "transferId",
                            transfer.transferId(),
                            "category",
                            "DAMAGED_IN_TRANSIT",
                            "status",
                            "DRAFT",
                            "lines",
                            shortLines.size(),
                            "shortfallQty",
                            shortfallQty,
                            "shortfallValue",
                            shortfallValue),
                    scope);
        }
        if (scope.userId() != null && scope.userId().equals(transfer.issuedBy())) {
            // wave 2, M5-16: allowed (a society with one manager sends and receives; the demo does) but
            // flagged, since one person moved stock between locations with nobody else looking.
            audit.record(
                    AUDIT_SELF_RECEIVED,
                    Subject.of("transfer", transfer.transferId()),
                    null,
                    Map.of("fromLocationId", transfer.fromLocationId(), "toLocationId", transfer.toLocationId()),
                    scope,
                    "The transfer was received by the person who issued it");
        }
        events.publish(new TransferReceived(
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.fromLocationId(),
                transfer.toLocationId(),
                lines.size()));
        if (shortfallId != null) {
            events.publish(new TransferShortfallDrafted(
                    shortfallId,
                    transfer.transferId(),
                    transfer.ownerEntityId(),
                    transfer.fromLocationId(),
                    transfer.toLocationId(),
                    shortLines.size(),
                    shortfallQty,
                    shortfallValue));
        }
        return transfer.transferId();
    }

    private static Map<UUID, BigDecimal> receivedQuantities(
            List<ReceiveTransfer.Line> requested, List<TransferStore.Line> issued) {
        Map<UUID, BigDecimal> issuedQuantities = new HashMap<>();
        for (TransferStore.Line line : issued) {
            issuedQuantities.put(line.lineId(), line.qty());
        }
        if (requested == null) {
            return issuedQuantities;
        }
        if (requested.isEmpty()) {
            throw new ProblemException("m5.transfer.receipt_lines_required");
        }

        Map<UUID, BigDecimal> actual = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (ReceiveTransfer.Line line : requested) {
            if (line == null || line.transferLineId() == null || line.receivedQty() == null) {
                throw new ProblemException("m5.transfer.receipt_line_invalid");
            }
            if (!seen.add(line.transferLineId())) {
                throw new ProblemException("m5.transfer.receipt_line_duplicate");
            }
            BigDecimal sent = issuedQuantities.get(line.transferLineId());
            if (sent == null) {
                throw new ProblemException("m5.transfer.receipt_line_unknown");
            }
            BigDecimal received = line.receivedQty();
            if (received.signum() <= 0 || received.scale() > 3) {
                throw new ProblemException("m5.transfer.receipt_qty_invalid");
            }
            if (received.compareTo(sent) > 0) {
                throw new ProblemException("m5.transfer.receipt_qty_exceeds_sent");
            }
            actual.put(line.transferLineId(), received);
        }
        if (actual.size() != issued.size()) {
            throw new ProblemException("m5.transfer.receipt_line_missing");
        }
        return actual;
    }
}
