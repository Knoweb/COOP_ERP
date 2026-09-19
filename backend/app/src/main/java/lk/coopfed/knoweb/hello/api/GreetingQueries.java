package lk.coopfed.knoweb.hello.api;

import lk.coopfed.knoweb.kernel.api.ScopeContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to greetings. Every method takes the caller's {@link ScopeContext}: the kernel
 * puts it on the database transaction and row-level security does the filtering. No query
 * ever adds "where owner_entity_id = ..." itself.
 */
public interface GreetingQueries {

    /** Empty when the greeting does not exist or the caller's scope may not see it. */
    Optional<GreetingView> find(UUID id, ScopeContext scope);

    /** The greetings visible in the caller's scope, newest first. */
    List<GreetingView> list(ScopeContext scope);
}
