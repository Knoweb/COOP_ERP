package lk.coopfed.archfixtures.m1party;

import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Violates the scope rule: a class outside a module's web package that reads the scope of the
 * HTTP request instead of taking a ScopeContext parameter. It would work for a web request and
 * fail for a till sync batch or a job, where there is no request.
 */
public class AsksForTheRequestScope {

    private final CurrentScope currentScope;

    public AsksForTheRequestScope(CurrentScope currentScope) {
        this.currentScope = currentScope;
    }

    public ScopeContext whoIsAsking() {
        return currentScope.get();
    }
}
