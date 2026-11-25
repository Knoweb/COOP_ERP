package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.CloseExpiryTask;
import lk.coopfed.knoweb.m5inventory.api.ExpiryTaskClosed;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes an expiry review task when its stock is exhausted, written off, or resolved (doc 25 section 4.6).
 */
@Service
@CommandHandler(permission = "inv.count.schedule")
public class CloseExpiryTaskHandler implements Handles<CloseExpiryTask, Void> {

    public static final String AUDIT_EXPIRY_CLOSED = "EXPIRY_TASK_CLOSED";

    private final ExpiryTaskStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    CloseExpiryTaskHandler(
            ExpiryTaskStore store, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(CloseExpiryTask command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);

        ExpiryTaskStore.ExpiryTask task = store.findById(command.taskId(), scope)
                .orElseThrow(
                        () -> new ProblemException("m5.expiry_task.not_found", Map.of("taskId", command.taskId())));

        if ("CLOSED".equals(task.status())) {
            return null;
        }

        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                """
                update inventory.expiry_task
                   set status = 'CLOSED', closed_at = ?
                 where task_id = ?
                """,
                now,
                task.taskId());

        String reason = command.reason() == null ? "RESOLVED" : command.reason();
        audit.record(
                AUDIT_EXPIRY_CLOSED,
                Subject.of("expiry_task", task.taskId()),
                Map.of("status", task.status()),
                Map.of("status", "CLOSED", "reason", reason),
                scope);

        events.publish(
                new ExpiryTaskClosed(task.taskId(), task.ownerEntityId(), task.locationId(), task.batchId(), reason));

        return null;
    }
}
