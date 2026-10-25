package lk.coopfed.knoweb.m5inventory.internal.opening;

import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

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

    /** The countersigner is a Federation officer acting entity-wide from the Federation's scope. */
    static void requireFederation(ScopeContext scope, JdbcTemplate jdbc) {
        requireUser(scope);
        boolean federation = scope.locationId() == null
                && Boolean.TRUE.equals(
                        jdbc.queryForObject("select kernel.scope_entity() = kernel.system_entity()", Boolean.class));
        if (!federation) {
            throw new ProblemException("m5.opening.federation_required");
        }
    }
}
