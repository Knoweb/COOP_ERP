package lk.coopfed.knoweb.m1party.internal.grant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.ExternalGrantQueries;
import lk.coopfed.knoweb.m1party.query.ExternalGrantView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Federation's register of grants, under its own row-level security (own_read). */
@Service
class ExternalGrantQueriesImpl implements ExternalGrantQueries {

    private final ExternalGrantRows rows;

    ExternalGrantQueriesImpl(ExternalGrantRows rows) {
        this.rows = rows;
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
}
