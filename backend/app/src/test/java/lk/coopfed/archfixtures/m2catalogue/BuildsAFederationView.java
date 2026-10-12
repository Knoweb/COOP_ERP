package lk.coopfed.archfixtures.m2catalogue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Violates the federation-view rule: a module class builds a read over every entity's rows
 * itself instead of taking it from {@code ScopedExecution.federationView()}.
 */
public class BuildsAFederationView {

    public ScopeContext everyone() {
        Scope wide = new Scope(new UUID(0, 0), null);
        return new ScopeContext(
                null,
                null,
                wide.entityId(),
                List.of(wide),
                wide,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }
}
