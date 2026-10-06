package lk.coopfed.knoweb.m8reporting.internal.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The projection pattern of 28A section 6: a read model fed by a consumer of events, written
 * in the OWN scope of each event's owner, and droppable, because {@link #apply} run over the
 * archive from the first event gives the same rows as the live consumer did (the equivalence
 * test, "the first test you write").
 *
 * <p>A projection subclasses this and declares its consumer on a method of its own, since the
 * kernel registers the {@code @EventConsumer} methods a class declares:
 *
 * <pre>
 * &#64;EventConsumer(types = "*", consumer = "m8.stock_position")
 * &#64;Transactional
 * public void on(JsonNode envelope, ScopeContext scope) { consume(envelope, scope); }
 * </pre>
 *
 * <p><b>Idempotent by construction.</b> The kernel's inbox delivers an event to a consumer
 * once, but a rebuild replays it, and an operator may replay a range twice. So every
 * {@link #apply} is written to change nothing the second time: a fact is inserted with
 * {@code ON CONFLICT DO NOTHING} on its event's key, and a current figure is overwritten with
 * the value the event reports, guarded by the event's order (never "add the delta").
 *
 * <p><b>Why this writes without a command handler.</b> The build lets only command handlers
 * write (ArchitectureTests, onlyHandlersWriteRule), because a write is a business fact that
 * must be audited and published. A projection row is neither: it is a copy of facts already
 * audited and published by the module that owns them, and auditing each copy would double the
 * audit log with nothing new. The rule exempts this package, and this package only (decided
 * 27 Sep 2026 on the architect's delegation; module README).
 */
public abstract class Projection {

    private final String name;
    private final String consumer;
    private final Set<String> types;
    protected final JdbcTemplate jdbc;
    private final ProjectionStateStore state;

    protected Projection(String name, Set<String> types, JdbcTemplate jdbc, ProjectionStateStore state) {
        this.name = name;
        this.consumer = "m8." + name;
        this.types = Set.copyOf(types);
        this.jdbc = jdbc;
        this.state = state;
    }

    public String name() {
        return name;
    }

    public String consumer() {
        return consumer;
    }

    public Set<String> types() {
        return types;
    }

    /**
     * What the consumer method calls: reads the envelope, ignores a type this projection does
     * not read (the subscription is to every type), applies the event and advances the state.
     * The caller's transaction holds the scope of the event's owner.
     */
    protected final void consume(JsonNode envelope, ScopeContext scope) {
        ProjectionEvent event = ProjectionEvent.of(envelope);
        if (!types.contains(event.type())) {
            return;
        }
        apply(event, scope);
        state.advance(name, consumer, event, scope);
        // Counted once the rows are committed, so a dashboard never caches what is not yet there.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            APPLIED.incrementAndGet();
                        }
                    });
        } else {
            APPLIED.incrementAndGet();
        }
    }

    /**
     * How many events the projections of this instance have applied since it started. The
     * dashboard's cache keys on it, so an event applied here shows at once; one applied by
     * another instance (the worker) shows once the cache's time is up.
     */
    public static long applied() {
        return APPLIED.get();
    }

    private static final java.util.concurrent.atomic.AtomicLong APPLIED = new java.util.concurrent.atomic.AtomicLong();

    /** Applies one event; must change nothing when applied a second time. */
    protected abstract void apply(ProjectionEvent event, ScopeContext scope);
}
