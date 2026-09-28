package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.RejectWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WriteOffRejected;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RejectWriteOff (25A section 6.3: "reason → REJECTED"): an approver refuses a write-off that was
 * submitted or witnessed. No stock moves; the WOF document keeps its number (a void keeps its
 * number, 24B N-4).
 *
 * <p>Guards, in order: an OWN scope; the write-off visible ({@code m5.writeoff.not_found});
 * REQUESTED or WITNESSED ({@code m5.writeoff.not_open}); a reason ({@code m5.reason_required}).
 *
 * <p>Mutation: REJECTED with the approver and the reason. Audit {@code WRITEOFF_REJECTED} with the
 * reason; event {@code writeoff.rejected.v1}.
 */
@Service
@CommandHandler(permission = "inv.writeoff.approve", requiresMfa = true)
class RejectWriteOffHandler implements Handles<RejectWriteOff, UUID> {

    static final String AUDIT_REJECTED = "WRITEOFF_REJECTED";

    private final WriteOffStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RejectWriteOffHandler(
            WriteOffStore store, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RejectWriteOff command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        WriteOffStore.Header writeOff = store.lock(command.writeOffId());
        if (!Set.of("REQUESTED", "WITNESSED").contains(writeOff.status())) {
            throw new ProblemException("m5.writeoff.not_open");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m5.reason_required");
        }
        String reason = command.reason().strip();
        jdbc.update(
                """
                update inventory.write_off
                   set status = 'REJECTED', approver_user_id = ?, decided_at = ?, reject_reason = ?
                 where write_off_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.instant()),
                reason,
                writeOff.writeOffId());

        audit.record(
                AUDIT_REJECTED,
                Subject.of("write_off", writeOff.writeOffId()),
                Map.of("status", writeOff.status()),
                Map.of("status", "REJECTED"),
                scope,
                reason);
        events.publish(new WriteOffRejected(writeOff.writeOffId(), writeOff.ownerEntityId(), writeOff.locationId()));
        return writeOff.writeOffId();
    }
}
