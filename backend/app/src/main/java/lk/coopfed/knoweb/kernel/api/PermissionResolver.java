package lk.coopfed.knoweb.kernel.api;

import java.util.Set;

/**
 * Answers whether a principal may perform an action in the scope of the request it is making
 * (19A section 3).
 *
 * <p>The permission catalogue, the role templates and the grants are M1's data in the
 * {@code security} schema; the kernel only reads them, resolves the union of the caller's
 * role grants in the scope and answers. An assignment at the entity applies at every location
 * of that entity; an assignment at a location applies there alone (doc 19 section 3.1), which
 * is why the check takes the whole {@link ScopeContext} and not a user id. Only the OWN class
 * resolves to anything: the read-only classes run no command.
 *
 * <p>K-03b implements it, cached per instance for ten minutes and emptied by the role and user
 * events of doc 14 section 1.3. The command interceptor is its first caller: permission check,
 * then MFA check, then idempotency, then the handler. The check is enforced when
 * {@code coop-erp.security.enforce-permissions} is true, which K-02 turns on once the user
 * behind a request is the token's and not a header's.
 */
public interface PermissionResolver {

    /** Every permission the principal holds in this scope: the union over its roles there. */
    Set<String> resolve(ScopeContext ctx);

    /**
     * Whether this principal, acting in this scope, holds this permission.
     *
     * @param ctx        the resolved principal and the scope chosen for the request
     * @param permission a permission code from M1's catalogue, for example
     *                   {@code gov.entity.activate}
     */
    default boolean allows(ScopeContext ctx, String permission) {
        return permission != null && resolve(ctx).contains(permission);
    }

    /** Whether the catalogue marks this permission as needing a fresh second factor. */
    boolean requiresMfa(String permission);
}
