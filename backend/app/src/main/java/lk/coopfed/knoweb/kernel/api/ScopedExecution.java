package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs operations within a specific {@link ScopeContext}.
 *
 * <p>Used by platform services, jobs, and event consumers that must act across tenant boundaries
 * (such as a {@link #federationView()}) or in a specific entity's {@link #own(UUID)} scope.
 */
public interface ScopedExecution {

    /** A read-only view over every entity's rows (the {@code fed_view} policies). */
    ScopeContext federationView();

    /** An OWN scope for the given entity. */
    ScopeContext own(UUID entityId);

    /** An OWN scope for the given entity and location. */
    ScopeContext own(UUID entityId, UUID locationId);

    /** Runs the work in the caller's transaction, applying the given scope. */
    <T> T inScope(ScopeContext scope, Supplier<T> work);

    /** Runs the work in a new transaction of its own, applying the given scope. */
    <T> T inOwnTransaction(ScopeContext scope, Supplier<T> work);
}
