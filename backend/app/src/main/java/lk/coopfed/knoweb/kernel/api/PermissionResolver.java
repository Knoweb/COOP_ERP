package lk.coopfed.knoweb.kernel.api;

/**
 * Answers whether a principal may perform an action in the scope of the request it is making
 * (19A section 3).
 *
 * <p>The permission catalogue, the role templates and the grants are M1's data in the
 * {@code security} schema; the kernel only reads them, resolves the union of the caller's
 * role grants and answers. A catalogue row's scope (LOCATION, ENTITY, FEDERATION) decides the
 * finest level a permission can be granted at, and an ENTITY grant is expanded to every
 * location of that entity at check time (doc 19 section 3.1), which is why the check takes
 * the whole {@link ScopeContext} and not a user id.
 *
 * <p>Ticket K-03b implements it, backed by a cache keyed on the user and the role version
 * {@code rv} of the token and invalidated by the role and user events of doc 14 section 1.3.
 * The kernel's command interceptor is its first caller: permission check, then MFA check,
 * then idempotency, then the handler.
 *
 * <p><strong>Today the server checks no permission at all.</strong> The web client hides
 * buttons from a role that may not use them, which is a courtesy; a direct request still
 * succeeds. This interface has no implementation until K-03b, so nothing injects it yet.
 *
 * <p>19A section 3 also gives {@code resolve(UUID userId, int catalogueVersion)} returning a
 * type it calls {@code Resolved}, and {@code limits(ScopeContext, String)} returning
 * {@code Optional<Limits>}. Neither {@code Resolved} nor {@code Limits} is defined in any
 * document, so neither method is declared here; both wait on the architect.
 */
public interface PermissionResolver {

    /**
     * Whether this principal, acting in this scope, holds this permission.
     *
     * @param ctx        the resolved principal and the scope chosen for the request
     * @param permission a permission code from M1's catalogue, for example
     *                   {@code gov.entity.activate}
     */
    boolean allows(ScopeContext ctx, String permission);
}
