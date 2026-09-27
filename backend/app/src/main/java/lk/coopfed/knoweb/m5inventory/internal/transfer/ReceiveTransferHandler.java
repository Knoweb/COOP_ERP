package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReceiveTransfer (25A section 6.3, "destination receive posts TRANSFER_IN with actuals"; doc 25
 * flow 6.6), at demo scope: the destination receives everything that was sent.
 *
 * <p><b>Own-location writes (PR #148).</b> The destination's session receives, and writes only
 * the destination's rows: one {@code transfer_receipt} row at the destination and the TRANSFER_IN
 * movements there. The source's transfer and lines are read (the {@code dest_read} policy),
 * never changed; the status is RECEIVED because the receipt row exists.
 *
 * <p>Guards, in order: an OWN scope ({@code m5.scope.own_required}); the transfer visible to the
 * scope ({@code m5.transfer.not_found}); the scope at the destination or entity-wide
 * ({@code m5.transfer.not_destination}: a session at the source reads the transfer but does not
 * receive it); not received before ({@code m5.transfer.already_received}).
 *
 * <p>Mutation: the receipt row; TRANSFER_IN per line at the cost the TRANSFER_OUT carried (the
 * entity average does not move: the stock never left the entity). Audit {@code TRANSFER_RECEIVED};
 * event {@code transfer.received.v1}.
 */
@Service
@CommandHandler(permission = "shop.transfer.receive")
class ReceiveTransferHandler implements Handles<ReceiveTransfer, UUID> {

    static final String AUDIT_RECEIVED = "TRANSFER_RECEIVED";

    private final TransferStore store;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReceiveTransferHandler(
            TransferStore store, StockLedger ledger, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.store = store;
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
        TransferStore.Header transfer = store.find(command.transferId())
                .orElseThrow(() -> new ProblemException(
                        "m5.transfer.not_found", Map.of("transferId", String.valueOf(command.transferId()))));
        if (scope.locationId() != null && !scope.locationId().equals(transfer.toLocationId())) {
            throw new ProblemException("m5.transfer.not_destination");
        }
        if (store.received(transfer.transferId())) {
            throw new ProblemException("m5.transfer.already_received");
        }

        jdbc.update(
                "insert into inventory.transfer_receipt (transfer_id, owner_entity_id, location_id, from_location_id,"
                        + " received_by) values (?, ?, ?, ?, ?)",
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.toLocationId(),
                transfer.fromLocationId(),
                scope.userId());
        List<TransferStore.Line> lines = store.lines(transfer.transferId());
        List<Movement> movements = new ArrayList<>();
        for (TransferStore.Line line : lines) {
            movements.add(new Movement(
                    transfer.toLocationId(),
                    line.batchId(),
                    LotCondition.GOOD,
                    MovementType.TRANSFER_IN,
                    line.qty(),
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
        events.publish(new TransferReceived(
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.fromLocationId(),
                transfer.toLocationId(),
                lines.size()));
        return transfer.transferId();
    }
}
