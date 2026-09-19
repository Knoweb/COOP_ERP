package lk.coopfed.knoweb.testsupport;

import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.stub.LoggingAuditFacade;
import lk.coopfed.knoweb.kernel.internal.stub.LoggingEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remembers every audit record and every event the application produces during a test, so
 * that a handler test can assert on them. It is what makes "every handler audits and
 * publishes" checkable per handler: what was recorded, about what, with which before and
 * after state, and whether it survived the transaction.
 *
 * <p>It sorts what it sees by the fate of the transaction, as the real tables of 19A will:
 * a record written in a transaction that rolls back is gone. So
 * <ul>
 *   <li>{@link #committedAudit()} and {@link #committedEvents()} are what would be in
 *       kernel.audit_event and kernel.event_outbox: assert on these</li>
 *   <li>{@link #rolledBackAudit()} and {@link #rolledBackEvents()} were attempted and then
 *       undone: useful to show that a failure rolled everything back together</li>
 * </ul>
 *
 * <p>It wraps the real 17A stubs, so their strict checks still run. Every integration test
 * gets it through {@link PostgresIntegrationTest}, as the field {@code kernel}, emptied
 * before each test. It replaces Mockito spies on the kernel: those count calls, but know
 * nothing about commit and rollback.
 */
@Component
@Primary
public class KernelRecorder implements AuditFacade, EventPublisher {

    /** One call of {@code audit.record(...)}, with every argument. */
    public record AuditRecord(
            String eventType,
            Subject subject,
            Object before,
            Object after,
            ScopeContext scope,
            String reason,
            UUID witnessUserId) {
    }

    private final LoggingAuditFacade realAudit;
    private final LoggingEventPublisher realEvents;

    private final List<AuditRecord> committedAudit = new CopyOnWriteArrayList<>();
    private final List<AuditRecord> rolledBackAudit = new CopyOnWriteArrayList<>();
    private final List<DomainEvent> committedEvents = new CopyOnWriteArrayList<>();
    private final List<DomainEvent> rolledBackEvents = new CopyOnWriteArrayList<>();
    private final AtomicReference<RuntimeException> nextPublishFailure = new AtomicReference<>();

    public KernelRecorder(LoggingAuditFacade realAudit, LoggingEventPublisher realEvents) {
        this.realAudit = realAudit;
        this.realEvents = realEvents;
    }

    @Override
    public void record(
            String eventType,
            Subject subject,
            Object before,
            Object after,
            ScopeContext scope,
            String reason,
            UUID witnessUserId) {
        realAudit.record(eventType, subject, before, after, scope, reason, witnessUserId);
        sortByOutcome(
                new AuditRecord(eventType, subject, before, after, scope, reason, witnessUserId),
                committedAudit,
                rolledBackAudit);
    }

    @Override
    public void publish(DomainEvent event) {
        RuntimeException failure = nextPublishFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        realEvents.publish(event);
        sortByOutcome(event, committedEvents, rolledBackEvents);
    }

    /** Audit records whose transaction committed: what kernel.audit_event would hold. */
    public List<AuditRecord> committedAudit() {
        return List.copyOf(committedAudit);
    }

    /** Audit records whose transaction rolled back: attempted, then undone. */
    public List<AuditRecord> rolledBackAudit() {
        return List.copyOf(rolledBackAudit);
    }

    /** Events whose transaction committed: what kernel.event_outbox would hold. */
    public List<DomainEvent> committedEvents() {
        return List.copyOf(committedEvents);
    }

    /** Events whose transaction rolled back. */
    public List<DomainEvent> rolledBackEvents() {
        return List.copyOf(rolledBackEvents);
    }

    /** Makes the next {@code events.publish(...)} throw, to prove a handler rolls back as a whole. */
    public void failNextPublishWith(RuntimeException failure) {
        nextPublishFailure.set(failure);
    }

    /** Called before each test by {@link PostgresIntegrationTest}. */
    public void reset() {
        committedAudit.clear();
        rolledBackAudit.clear();
        committedEvents.clear();
        rolledBackEvents.clear();
        nextPublishFailure.set(null);
    }

    /** The real stubs have already insisted that a transaction is open, so one always is here. */
    private static <T> void sortByOutcome(T item, List<T> whenCommitted, List<T> whenRolledBack) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                (status == STATUS_COMMITTED ? whenCommitted : whenRolledBack).add(item);
            }
        });
    }
}
