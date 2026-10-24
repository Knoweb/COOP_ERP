package lk.coopfed.knoweb.m5inventory.internal.transfer;

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
import lk.coopfed.knoweb.m5inventory.api.DispatchTransfer;
import lk.coopfed.knoweb.m5inventory.api.TransferDispatched;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatches an issued transfer (doc 25 section 4.5, ISSUED to IN_TRANSIT).
 *
 * <p>The dispatch is an insert-only source fact. Stock already left through TRANSFER_OUT when the
 * transfer was issued, so dispatch posts no movement. Its event makes the expected transfer
 * available to the destination snapshot.
 */
@Service
@CommandHandler(permission = "inv.transfer.issue")
class DispatchTransferHandler implements Handles<DispatchTransfer, UUID> {

    static final String AUDIT_DISPATCHED = "TRANSFER_DISPATCHED";

    private final TransferStore store;
    private final TransferLifecycleLock lifecycleLock;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    DispatchTransferHandler(
            TransferStore store,
            TransferLifecycleLock lifecycleLock,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.lifecycleLock = lifecycleLock;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DispatchTransfer command, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
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
        if (store.dispatched(transfer.transferId())) {
            throw new ProblemException("m5.transfer.already_dispatched");
        }

        int inserted = jdbc.update(
                "insert into inventory.transfer_dispatch (transfer_id, owner_entity_id, location_id,"
                        + " to_location_id, dispatched_by) values (?, ?, ?, ?, ?)"
                        + " on conflict (transfer_id) do nothing",
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.fromLocationId(),
                transfer.toLocationId(),
                scope.userId());
        if (inserted == 0) {
            // Covers two different idempotency keys racing after the explicit guard above.
            throw new ProblemException("m5.transfer.already_dispatched");
        }
        int lines = store.lines(transfer.transferId()).size();

        audit.record(
                AUDIT_DISPATCHED,
                Subject.of("transfer", transfer.transferId()),
                Map.of("status", "ISSUED"),
                Map.of("status", "IN_TRANSIT", "toLocationId", transfer.toLocationId(), "lines", lines),
                scope);
        events.publish(new TransferDispatched(
                transfer.transferId(),
                transfer.ownerEntityId(),
                transfer.fromLocationId(),
                transfer.toLocationId(),
                lines));
        return transfer.transferId();
    }
}
