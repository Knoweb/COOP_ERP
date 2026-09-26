package lk.coopfed.knoweb.m1party.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The external grants of M1 (21A section 7: ListExternalGrants). The resolution of an
 * EXTERNAL_TIMEBOXED principal's entities (21A's ResolveScope for EXTERNAL users) is the
 * kernel's: {@code kernel.internal.security.JdbcUserScopes.grantsOf} reads {@code
 * security.external_grant} as the federation-wide viewer, ACTIVE grants of an ACTIVE grantee
 * whose window contains now, cached for a minute at most and emptied by the grant and user
 * events. Nothing here resolves a grant.
 */
public interface ExternalGrantQueries {

    /** Every grant, active, expired and revoked, latest end first: the Federation's register. */
    List<ExternalGrantView> listExternalGrants(ScopeContext scope);

    /** One grant, when the scope can see it. */
    Optional<ExternalGrantView> getExternalGrant(UUID grantId, ScopeContext scope);
}
