package lk.coopfed.knoweb.m1party.internal.relationship;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The gate on a credit limit, wherever one is set (doc 21 flow 6.4; wave 2, M1A-06; CR-21A-7):
 * opening a relationship with a limit, activating a draft that carries one, and amending the
 * limit each need {@code bil.creditlimit.change} beside the command's own permission, and a
 * second factor presented recently. Guards in the handlers, not annotation attributes, because
 * they apply only when there is a limit; the interceptor cannot know that before the handler
 * reads the terms.
 *
 * <p>The resolver is required: an instance without one fails at start rather than skip the
 * check, as the K-03b placeholder of AmendRelationshipTerms did (M1A-06).
 */
@Component
class CreditLimitGate {

    /** Owned by M4, granted by M1 (21A section 3.3). */
    static final String PERMISSION = "bil.creditlimit.change";

    /** How recent the second factor must be for a limit change; the kernel's config setter uses ten minutes too. */
    static final String MFA_MAX_AGE = "m1.relationship.credit_limit_mfa_max_age";

    static final Duration DEFAULT_MFA_MAX_AGE = Duration.ofMinutes(10);

    private final PermissionResolver permissions;
    private final ConfigRegistry config;
    private final Clock clock;

    CreditLimitGate(PermissionResolver permissions, ConfigRegistry config, Clock clock) {
        this.permissions = permissions;
        this.config = config;
        this.clock = clock;
    }

    /** The extra permission, then the step-up. */
    void require(ScopeContext scope) {
        if (!permissions.allows(scope, PERMISSION)) {
            throw new ProblemException(
                    "m1.relationship.credit_limit_permission_required", Map.of("permission", PERMISSION));
        }
        Duration maxAge = config.getDuration(MFA_MAX_AGE, scope, DEFAULT_MFA_MAX_AGE);
        Instant freshEnough = clock.instant().minus(maxAge);
        if (scope.mfaAt() == null || scope.mfaAt().isBefore(freshEnough)) {
            throw new ProblemException("mfa.required", Map.of("permission", PERMISSION));
        }
    }
}
