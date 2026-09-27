package lk.coopfed.knoweb.m5inventory.internal.opening;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guards the two signatures share. */
final class OpeningBalanceGuards {

    private OpeningBalanceGuards() {}

    /** A signature is a person's, in an owner's scope: never a device's or the system's. */
    static void requireUser(ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
        if (scope.userId() == null) {
            throw new ProblemException("m5.opening.user_required");
        }
    }
}
