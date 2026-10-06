package lk.coopfed.knoweb.m1party.internal.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * Who may reset or disable whose credentials (wave 2, M1A-01; CR-21A-7;
 * docs/progress/deviations/2026-10-06-wave2-m1-administration.md (1)). ResetCredential, the
 * UpdateUser kind change that closes the back office, and DeactivateUser are refused when the
 * target holds, at the entity, a permission the catalogue marks {@code requires_mfa} (user and
 * role management, the credit limit, the approvals) that the caller does not hold: a holder of
 * gov.user.manage resets a cashier's password, never the role manager's, and so never receives a
 * temporary password that opens an account above their own.
 *
 * <p>Equal or lower is enough, not "everything the target holds": the seeded Entity Administrator
 * holds no finance codes, and must still reset the society's cashier. A guard, so it reads facts
 * and writes nothing.
 */
@Component
class UserRank {

    private final UserFacts facts;
    private final PermissionResolver permissions;

    UserRank(UserFacts facts, PermissionResolver permissions) {
        this.facts = facts;
        this.permissions = permissions;
    }

    /** {@code m1.user.target_outranks_caller} when the target holds a sensitive code the caller lacks. */
    void requireCallerNotOutranked(AppUser target, ScopeContext scope) {
        Set<String> sensitive = facts.sensitivePermissionsHeld(target.getId(), scope.entityId());
        if (sensitive.isEmpty()) {
            return;
        }
        Set<String> held = permissions.resolve(scope);
        List<String> above =
                sensitive.stream().filter(code -> !held.contains(code)).sorted().toList();
        if (!above.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", target.getId());
            params.put("permissions", String.join(", ", above));
            throw new ProblemException("m1.user.target_outranks_caller", params);
        }
    }

    /**
     * Nobody resets their own second factor through the command ({@code m1.user.credential_self}):
     * a session taken over with a password would otherwise enrol the taker's own second factor.
     * Another user manager of equal rank does it.
     */
    static void requireNotOwnSecondFactor(AppUser target, ScopeContext scope) {
        if (target.getId().equals(scope.userId())) {
            throw new ProblemException("m1.user.credential_self", Map.of("userId", target.getId()));
        }
    }
}
