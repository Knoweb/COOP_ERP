package lk.coopfed.knoweb.kernel.internal.security;

import java.time.Clock;
import java.time.Duration;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Whether the caller's second factor is fresh enough for an action that asks for one (doc 19
 * section 2.2: "unless mfa_at is within the freshness window (default 12 hours; per-entity
 * configurable)"; 19A section 2: "the freshness window from the configuration register"). One
 * rule for the command interceptor, the configuration register and the kernel's own
 * operations, read from {@code security.mfa.freshness} in the caller's scope, so an entity
 * that tightens the window tightens it everywhere at once.
 */
@Component
public class StepUp {

    static final String FRESHNESS_KEY = "security.mfa.freshness";

    /** Doc 19 section 2.2: twelve hours unless the register says otherwise. */
    static final Duration DEFAULT_FRESHNESS = Duration.ofHours(12);

    private final ObjectProvider<ConfigRegistry> config;
    private final Clock clock;

    public StepUp(ObjectProvider<ConfigRegistry> config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** True when the second factor was presented within the window that applies to the caller. */
    public boolean isFresh(ScopeContext ctx) {
        if (ctx == null || ctx.mfaAt() == null) {
            return false;
        }
        return !ctx.mfaAt().isBefore(clock.instant().minus(freshness(ctx)));
    }

    Duration freshness(ScopeContext ctx) {
        ConfigRegistry registry = config.getIfAvailable();
        if (registry == null) {
            return DEFAULT_FRESHNESS;
        }
        try {
            return registry.getDuration(FRESHNESS_KEY, ctx, DEFAULT_FRESHNESS);
        } catch (RuntimeException unreadable) {
            // A register that cannot be read (no scope on the connection, a value that is not
            // a duration) never widens the window: the default is the safe side.
            return DEFAULT_FRESHNESS;
        }
    }
}
