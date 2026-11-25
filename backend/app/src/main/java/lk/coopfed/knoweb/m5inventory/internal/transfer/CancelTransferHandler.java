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
import lk.coopfed.knoweb.m5inventory.api.CancelTransfer;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TransferCancelled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cancels an unreceived transfer and restores its issued stock at the original source. */
@Service
@CommandHandler(permission = "inv.transfer.issue")
class CancelTransferHandler implements Handles<CancelTransfer, UUID> {

    static final String AUDIT_CANCELLED = "TRANSFER_CANCELLED";

    private final TransferStore store;
    private final TransferLifecycleLock lifecycleLock;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    CancelTransferHandler(
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
    public UUID handle(CancelTransfer command, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
        if (command.reason() == null
                || command.reason().isBlank()
                || command.reason().strip().length() > 500) {
            throw new ProblemException("m5.transfer.cancellation_reason_invalid");
        }

        lifecycleLock.acquire(command.transferId());
        TransferStore.Header transfer = store.find(command.transferId())
                .orElseThrow(() -> new ProblemException(
                        "m5.transfer.not_found", Map.of("transferId", String.valueOf(command.transferId()))));
        if (scope.locationId() != null && !scope.locationId().equals(transfer.fromLocationId())) {
            throw new ProblemException("m5.transfer.not_source");
        }
        if (store.cancelled(transfer.transferId())) {
            throw new ProblemException("m5.transfer.already_cancelled");
        }
        if (store.received(transfer.transferId())) {
            throw new ProblemException("m5.transfer.already_received");
        }

        boolean dispatched = store.dispatched(transfer.transferId());
        if (dispatched && (!command.physicalReturnVerified() || scope.userId() == null)) {
            throw new ProblemException("m5.transfer.physical_return_required");
        }
        String previousStatus = dispatched ? "IN_TRANSIT" : "ISSUED";

        int inserted = jdbc.update(
                "insert into inventory.transfer_cancellation"
                        + " (transfer_id, owner_entity_id, location_id, to_location_id, cancelled_by, reason,"
                        + " cancelled_from_status, physical_return_verified_by, physical_return_verified_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, case when ? then now() else null end)"
                        + " on conflict (transfer_id) do nothing",
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.fromLocationId(),
                transfer.toLocationId(),
                scope.userId(),
                command.reason().strip(),
                previousStatus,
                dispatched ? scope.userId() : null,
                dispatched);
        if (inserted == 0) {
            throw new ProblemException("m5.transfer.already_cancelled");
        }

        List<TransferStore.Line> lines = store.lines(transfer.transferId());
        List<Movement> reversals = new ArrayList<>();
        for (TransferStore.Line line : lines) {
            reversals.add(new Movement(
                    transfer.fromLocationId(),
                    line.batchId(),
                    LotCondition.GOOD,
                    MovementType.TRANSFER_IN,
                    line.qty(),
                    line.unitCost(),
                    line.lineId()));
        }
        ledger.post(new PostMovements(transfer.transferId(), null, null, reversals), scope);

        audit.record(
                AUDIT_CANCELLED,
                Subject.of("transfer", transfer.transferId()),
                Map.of("status", previousStatus),
                Map.of(
                        "status",
                        "CANCELLED",
                        "reason",
                        command.reason().strip(),
                        "physicalReturnVerified",
                        dispatched,
                        "lines",
                        lines.size()),
                scope);
        events.publish(new TransferCancelled(
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.fromLocationId(),
                transfer.toLocationId(),
                previousStatus,
                dispatched,
                lines.size()));
        return transfer.transferId();
    }
}
