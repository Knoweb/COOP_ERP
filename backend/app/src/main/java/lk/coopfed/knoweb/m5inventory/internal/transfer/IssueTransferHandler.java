package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.PostedMovement;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TransferIssued;
import lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IssueTransfer (25A section 6.3, "same entity; availability at source; issuance; TRANSFER_OUT at
 * cost"; doc 25 flow 6.6), at demo scope: issue and dispatch in one step. The stock leaves the
 * source as TRANSFER_OUT at the entity average and is in transit, still the entity's, until the
 * destination receives it ({@link ReceiveTransferHandler}).
 *
 * <p>Guards, in order: an OWN scope ({@code m5.scope.own_required}); the source one of the scope
 * entity's locations that the scope reads ({@code m5.location.not_in_scope}); the destination
 * another location of the same entity that the scope reads ({@code m5.transfer.same_location},
 * {@code m5.transfer.destination_invalid}): inv.transfer.issue is an ENTITY permission (25A
 * section 3.1), so the issuer is an entity-wide user, and a session held to the source cannot
 * see the destination; at least one line ({@code m5.transfer.lines_required}); each line a batch
 * and a quantity above zero with at most three decimals ({@code m5.transfer.line_invalid}), the
 * batch known to M2 ({@code m5.batch.not_found}), and the source's GOOD lot of it holding the
 * quantity, less what open pick lists hold of it, the lots locked in the ledger's order (wave 2,
 * M5-12; {@code m5.transfer.insufficient_stock}: stock that is not there is not sent).
 *
 * <p>Mutation: the transfer and its lines, all at the source location (the source's rows); the
 * ledger's TRANSFER_OUT movements there, citing the transfer. Audit {@code TRANSFER_ISSUED}, and
 * {@code TRANSFER_REQUEST_SHORT} (REVIEW) for a request sent short (M5-03); event
 * {@code transfer.issued.v1}. The XFR document of 25A is deferred for the demo: the movements cite
 * the transfer's id (README, deviation 13).
 */
@Service
@CommandHandler(permission = "inv.transfer.issue")
class IssueTransferHandler implements Handles<IssueTransfer, UUID> {

    static final String AUDIT_ISSUED = "TRANSFER_ISSUED";
    static final String AUDIT_REQUEST_SHORT = "TRANSFER_REQUEST_SHORT";

    private final TransferStore store;
    private final StockOnHand stock;
    private final PartyQueries party;
    private final BatchQueries batches;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    IssueTransferHandler(
            TransferStore store,
            StockOnHand stock,
            PartyQueries party,
            BatchQueries batches,
            StockLedger ledger,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.stock = stock;
        this.party = party;
        this.batches = batches;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(IssueTransfer command, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
        UUID from = command.fromLocationId();
        UUID to = command.toLocationId();
        if (!entityLocation(from, scope)) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", String.valueOf(from)));
        }
        if (from.equals(to)) {
            throw new ProblemException("m5.transfer.same_location");
        }
        if (!entityLocation(to, scope)) {
            throw new ProblemException("m5.transfer.destination_invalid", Map.of("locationId", String.valueOf(to)));
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ProblemException("m5.transfer.lines_required");
        }
        for (IssueTransfer.Line line : command.lines()) {
            requireLine(line);
        }
        if (command.transferRequestId() != null) {
            // A request is fulfilled once: a redelivered approval finds the transfer it issued.
            List<UUID> issued = jdbc.queryForList(
                    "select transfer_id from inventory.transfer where transfer_request_id = ?",
                    UUID.class,
                    command.transferRequestId());
            if (!issued.isEmpty()) {
                return issued.get(0);
            }
        }
        List<UUID> skus = new ArrayList<>();
        Map<UUID, BigDecimal> perBatch = new LinkedHashMap<>();
        for (IssueTransfer.Line line : command.lines()) {
            BatchView batch = batches.getBatch(line.batchId(), scope)
                    .orElseThrow(() -> new ProblemException("m5.batch.not_found", Map.of("batchId", line.batchId())));
            skus.add(batch.skuId());
            perBatch.merge(line.batchId(), line.qty(), BigDecimal::add);
        }
        // wave 2, M5-12: the lots locked in the ledger's order, so the check and the posting see the
        // same quantity; what an open pick list holds for a delivery note is not free to send.
        Map<StockOnHand.LotRef, BigDecimal> held = stock.lockLots(
                from,
                perBatch.keySet().stream()
                        .map(batch -> new StockOnHand.LotRef(batch, LotCondition.GOOD.name()))
                        .toList());
        perBatch.forEach((batch, qty) -> {
            BigDecimal free = held.get(new StockOnHand.LotRef(batch, LotCondition.GOOD.name()))
                    .subtract(store.reserved(from, batch));
            if (free.compareTo(qty) < 0) {
                throw new ProblemException("m5.transfer.insufficient_stock", Map.of("batchId", batch));
            }
        });

        UUID id = Ids.next();
        jdbc.update(
                "insert into inventory.transfer (transfer_id, owner_entity_id, location_id, to_location_id, issued_by,"
                        + " transfer_request_id) values (?, ?, ?, ?, ?, ?)",
                id,
                scope.entityId(),
                from,
                to,
                scope.userId(),
                command.transferRequestId());
        List<UUID> lineIds = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        for (IssueTransfer.Line line : command.lines()) {
            UUID lineId = Ids.next();
            lineIds.add(lineId);
            movements.add(new Movement(
                    from,
                    line.batchId(),
                    LotCondition.GOOD,
                    MovementType.TRANSFER_OUT,
                    line.qty().negate(),
                    null,
                    lineId));
        }
        List<PostedMovement> posted = ledger.post(new PostMovements(id, null, null, movements), scope);
        for (int i = 0; i < command.lines().size(); i++) {
            IssueTransfer.Line line = command.lines().get(i);
            jdbc.update(
                    """
                    insert into inventory.transfer_line
                        (line_id, transfer_id, owner_entity_id, location_id, to_location_id, line_no, batch_id, sku_id,
                         qty, unit_cost)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    lineIds.get(i),
                    id,
                    scope.entityId(),
                    from,
                    to,
                    i + 1,
                    line.batchId(),
                    skus.get(i),
                    line.qty(),
                    posted.get(i).unitCostAtMovement());
        }

        audit.record(AUDIT_ISSUED, Subject.of("transfer", id), null, auditAfter(from, to, command), scope);
        if (!command.shortfalls().isEmpty()) {
            // wave 2, M5-03: the request is filled once; what could not be sent is on the exception
            // report and the shop raises a new request for it, as an indent book works.
            List<Map<String, Object>> shortLines = new ArrayList<>();
            for (IssueTransfer.Shortfall shortfall : command.shortfalls()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("skuId", shortfall.skuId());
                entry.put("wanted", shortfall.wanted().toPlainString());
                entry.put("sent", shortfall.sent().toPlainString());
                shortLines.add(entry);
            }
            audit.record(
                    AUDIT_REQUEST_SHORT,
                    Subject.of("transfer", id),
                    null,
                    Map.of("transferRequestId", String.valueOf(command.transferRequestId()), "lines", shortLines),
                    scope,
                    "The approved request could not be filled in full; the shop raises a new request for the rest");
        }
        events.publish(new TransferIssued(
                id,
                scope.entityId(),
                from,
                to,
                command.lines().size(),
                command.transferRequestId(),
                command.shortfalls().size()));
        return id;
    }

    private static Map<String, Object> auditAfter(UUID from, UUID to, IssueTransfer command) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fromLocationId", from);
        after.put("toLocationId", to);
        after.put("status", "IN_TRANSIT");
        after.put("lines", command.lines().size());
        if (command.transferRequestId() != null) {
            after.put("transferRequestId", command.transferRequestId());
        }
        return after;
    }

    /** A location of the scope entity that the scope reads (M1). */
    private boolean entityLocation(UUID location, ScopeContext scope) {
        return location != null
                && party.getLocation(location, scope)
                        .filter(l -> scope.entityId().equals(l.ownerEntityId()))
                        .isPresent();
    }

    private static void requireLine(IssueTransfer.Line line) {
        boolean valid = line != null
                && line.batchId() != null
                && line.qty() != null
                && line.qty().signum() > 0
                && line.qty().stripTrailingZeros().scale() <= 3;
        if (!valid) {
            throw new ProblemException("m5.transfer.line_invalid");
        }
    }
}
