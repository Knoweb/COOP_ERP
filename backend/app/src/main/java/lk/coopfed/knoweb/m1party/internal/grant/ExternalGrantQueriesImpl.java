package lk.coopfed.knoweb.m1party.internal.grant;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.ExternalGrantQueries;
import lk.coopfed.knoweb.m1party.query.ExternalGrantView;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExternalGrantQueriesImpl implements ExternalGrantQueries {

    private final ExternalGrantRows rows;
    private final GranteeGrants granteeGrants;

    ExternalGrantQueriesImpl(ExternalGrantRows rows, GranteeGrants granteeGrants) {
        this.rows = rows;
        this.granteeGrants = granteeGrants;
    }

    @Override
    public Set<UUID> activeGrantedEntities(UUID userId, Instant at) {
        if (userId == null || at == null) {
            return Set.of();
        }
        return granteeGrants.activeEntities(asGrantee(userId), at);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExternalGrantView> listExternalGrants(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        return rows.all();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExternalGrantView> getExternalGrant(UUID grantId, ScopeContext scope) {
        if (grantId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return rows.find(grantId);
    }

    /**
     * The grantee as a principal with no tenant scope: the kernel's customizer sets its user id
     * on the session and the class NONE, so no tenant policy admits anything and the grantee's
     * own grants are read through {@code grantee_read} alone.
     */
    private static ScopeContext asGrantee(UUID userId) {
        return new ScopeContext(
                userId, null, null, List.of(), null, PolicyClass.NONE, Set.of(), null, Locale.ENGLISH, null);
    }

    /**
     * A bean of its own so that the call passes the transaction proxy: a new transaction, with the
     * grantee's user id on its session, whatever transaction (and scope) the caller is in.
     */
    @Component
    static class GranteeGrants {

        private final ExternalGrantRows rows;

        GranteeGrants(ExternalGrantRows rows) {
            this.rows = rows;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public Set<UUID> activeEntities(ScopeContext grantee, Instant at) {
            return Set.copyOf(rows.activeEntitiesOfGrantee(grantee.userId(), at));
        }
    }
}
