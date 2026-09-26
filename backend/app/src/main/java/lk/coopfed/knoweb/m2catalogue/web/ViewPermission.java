package lk.coopfed.knoweb.m2catalogue.web;

import java.util.Map;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of the slice declare x-permission cat.sku.view, and no command interceptor runs
 * for a read, so the controllers check it: for the OWN class (the read-only classes resolve
 * no permission; row-level security is what limits them), and only when enforcement is on, as
 * for commands (coop-erp.security.enforce-permissions).
 */
final class ViewPermission {

    static final String VIEW = "cat.sku.view";

    private final PermissionResolver permissions;
    private final boolean enforcePermissions;

    ViewPermission(PermissionResolver permissions, boolean enforcePermissions) {
        this.permissions = permissions;
        this.enforcePermissions = enforcePermissions;
    }

    void require(ScopeContext scope) {
        if (!enforcePermissions || scope == null || scope.policyClass() != PolicyClass.OWN) {
            return;
        }
        if (!permissions.allows(scope, VIEW)) {
            throw new ProblemException("permission.denied", Map.of("permission", VIEW));
        }
    }
}
