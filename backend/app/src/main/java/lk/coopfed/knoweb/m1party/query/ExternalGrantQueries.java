package lk.coopfed.knoweb.m1party.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The external grants of M1 (21A section 7: ResolveScope for EXTERNAL users, and
 * ListExternalGrants).
 */
public interface ExternalGrantQueries {

    /**
     * The entities an EXTERNAL_TIMEBOXED principal may read at this instant: the union of the
     * scopes of every grant of this user that is ACTIVE and whose window contains {@code at}
     * ({@code valid_from <= at < valid_until}). Empty when there is none, and an empty grant
     * reads nothing under the {@code ext_view} policies.
     *
     * <p>It is what the scope of an external user is built from ({@code
     * ScopeContext.grantedEntities}, which the kernel sets on the session as
     * {@code app.granted_entities}). It needs no scope of its own: it reads the user's own
     * grants (policy {@code grantee_read}, m1security V0009) in a transaction of its own, so it
     * may be called before the caller's scope exists, and from inside another transaction
     * without touching that transaction's scope. The window is checked here and not left to the
     * expiry job: a grant stops admitting at {@code valid_until}, and a revoked one at once.
     */
    Set<UUID> activeGrantedEntities(UUID userId, Instant at);

    /** Every grant, active, expired and revoked, latest end first: the Federation's register. */
    List<ExternalGrantView> listExternalGrants(ScopeContext scope);

    /** One grant, when the scope can see it. */
    Optional<ExternalGrantView> getExternalGrant(UUID grantId, ScopeContext scope);
}
