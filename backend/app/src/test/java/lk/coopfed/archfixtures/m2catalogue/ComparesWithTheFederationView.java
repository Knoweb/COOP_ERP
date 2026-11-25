package lk.coopfed.archfixtures.m2catalogue;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** Allowed by the federation-view rule: it reads the caller's class and builds no scope. */
public class ComparesWithTheFederationView {

    public boolean seesEveryone(ScopeContext scope) {
        return scope.policyClass() == PolicyClass.FEDERATION_VIEW;
    }
}
