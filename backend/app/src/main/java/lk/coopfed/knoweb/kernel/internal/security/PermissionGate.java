package lk.coopfed.knoweb.kernel.internal.security;

import java.util.Map;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one permission rule of 19A section 3, for the command interceptor, the read interceptor
 * and the kernel's own operations (an enrolment code, K-08): the permission against what the
 * caller holds in the scope ({@link PermissionResolver}), then, for a command, a fresh second
 * factor where the catalogue asks for one ({@link StepUp}: the register's window). Refuses only
 * when enforcement is on ({@code coop-erp.security.enforce-permissions}); until then a would-be
 * refusal is logged, so the gap between the roles and the handlers shows before it bites. One
 * place, so that a change to the rule (the freshness window from the register, doc 19 DR-7)
 * reaches every caller at once instead of a copy of the rule keeping the old one.
 *
 * <p>Two rules by class, decided 27 September 2026 (CR-19A-9): a command runs in the OWN class
 * only, whatever the resolver says (doc 18 section 3.7: the read-only classes write nothing;
 * said here and not left to the resolver, because a read code and a command code may be one
 * string, as {@code gov.external.grant} is); a read passes when the caller holds its code, and
 * {@link SliceOperations#AUTHENTICATED} is held by every signed-in principal.
 */
@Component
public class PermissionGate {

    private static final Logger log = LoggerFactory.getLogger(PermissionGate.class);

    private final PermissionResolver permissions;
    private final StepUp stepUp;
    private final boolean enforcePermissions;
    private final String stepUpUrl;

    public PermissionGate(
            PermissionResolver permissions,
            StepUp stepUp,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions,
            @Value("${coop-erp.security.oidc.step-up-url:}") String stepUpUrl) {
        this.permissions = permissions;
        this.stepUp = stepUp;
        this.enforcePermissions = enforcePermissions;
        this.stepUpUrl = stepUpUrl;
    }

    /**
     * A command: passes, or throws {@code permission.denied} (403) or {@code mfa.required} (401
     * with the provider's step-up address, which the web shell takes the user to; 17A section 7).
     */
    public void require(ScopeContext scope, String permission) {
        if (permission == null || permission.isBlank()) {
            return;
        }

        boolean allowed = scope.policyClass() == PolicyClass.OWN && permissions.allows(scope, permission);
        boolean mfaFresh = !permissions.requiresMfa(permission) || stepUp.isFresh(scope);

        if (!enforcePermissions) {
            if (!allowed || !mfaFresh) {
                log.info(
                        "Would refuse {} for user {} at entity {} ({}): allowed={}, mfaFresh={} (not enforced)",
                        permission,
                        scope.userId(),
                        scope.entityId(),
                        scope.policyClass(),
                        allowed,
                        mfaFresh);
            }
            return;
        }
        if (!allowed) {
            throw new ProblemException("permission.denied", Map.of("permission", permission));
        }
        if (!mfaFresh) {
            throw new ProblemException("mfa.required", Map.of("permission", permission, "stepUpUrl", stepUpUrl));
        }
    }

    /**
     * A read (a GET of a slice): passes when the caller holds the code in the active scope, or
     * throws {@code permission.denied} (403). No second factor: doc 19 section 2.2 puts the
     * challenge at the action, and a read changes nothing.
     */
    public void requireRead(ScopeContext scope, String permission) {
        if (permission == null || permission.isBlank() || SliceOperations.AUTHENTICATED.equals(permission)) {
            return;
        }
        boolean allowed = permissions.allows(scope, permission);
        if (!enforcePermissions) {
            if (!allowed) {
                log.info(
                        "Would refuse the read {} for user {} at entity {} ({}) (not enforced)",
                        permission,
                        scope.userId(),
                        scope.entityId(),
                        scope.policyClass());
            }
            return;
        }
        if (!allowed) {
            throw new ProblemException("permission.denied", Map.of("permission", permission));
        }
    }
}
