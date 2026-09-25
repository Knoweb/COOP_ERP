package lk.coopfed.knoweb.m1party.internal.user;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The till PIN policy (doc 19 section 2.1 and DR-5; doc 21 sections 3.6 and 7): digits only,
 * four to six of them as the entity configures, and none of the last three again. The lockout
 * half of the policy (five attempts, then fifteen minutes) is enforced where the PIN is typed,
 * on the till, which reads the same items from its configuration snapshot (they are
 * till-visible); nothing here counts attempts.
 *
 * <p>Every value is a configuration item (AGENTS.md: never a hard-coded limit), resolved for
 * the caller's scope, the user's home entity. The literals below are only what applies when
 * the register cannot be read at all, and they are the register's own defaults.
 */
@Component
class PinPolicy {

    static final String LENGTH_MIN = "security.pin.length_min";
    static final String LENGTH_MAX = "security.pin.length_max";
    static final String HISTORY_DEPTH = "security.pin.history_depth";

    /** The outer bounds of doc 19 DR-5, which no entity setting can widen. */
    static final int SHORTEST = 4;

    static final int LONGEST = 6;

    private static final int DEFAULT_HISTORY_DEPTH = 3;

    private final ConfigRegistry config;
    private final PinHasher hasher;

    PinPolicy(ConfigRegistry config, PinHasher hasher) {
        this.config = config;
        this.hasher = hasher;
    }

    /**
     * Refuses a PIN that breaks the policy; the problem's parameters carry the rule, never the
     * PIN. {@code recentHashes} are the user's current and earlier PIN hashes, newest first.
     */
    void check(CharSequence pin, List<String> recentHashes, ScopeContext scope) {
        if (pin == null || pin.isEmpty()) {
            throw new ProblemException("m1.user.pin_required");
        }
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') {
                // Western Arabic digits only (doc 19 DR-6): the till's keypad has no other.
                throw new ProblemException("m1.user.pin_digits_only");
            }
        }
        int min = Math.max(SHORTEST, config.getInt(LENGTH_MIN, scope, SHORTEST));
        int max = Math.min(LONGEST, config.getInt(LENGTH_MAX, scope, LONGEST));
        if (max < min) {
            // A register that says 6 to 4 is misconfigured; the wider reading of DR-5 applies.
            min = SHORTEST;
            max = LONGEST;
        }
        if (pin.length() < min || pin.length() > max) {
            throw new ProblemException("m1.user.pin_length", Map.of("min", min, "max", max));
        }
        int depth = historyDepth(scope);
        for (int i = 0; i < recentHashes.size() && i < depth; i++) {
            if (hasher.matches(pin, recentHashes.get(i))) {
                throw new ProblemException("m1.user.pin_reused", Map.of("depth", depth));
            }
        }
    }

    /** How many PINs, the current one included, a new PIN must differ from. */
    int historyDepth(ScopeContext scope) {
        return Math.max(1, config.getInt(HISTORY_DEPTH, scope, DEFAULT_HISTORY_DEPTH));
    }

    String hash(CharSequence pin) {
        return hasher.hash(pin);
    }
}
