package lk.coopfed.knoweb.m1party.internal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;

/** The PIN policy's rules one by one, with a register and a hasher of the test's own. */
class PinPolicyTest {

    private final Map<String, String> register = new HashMap<>();
    private final ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), UUID.randomUUID(), null);
    private final PinPolicy policy = new PinPolicy(new MapRegistry(register), new PlainHasher());

    @Test
    void fourToSixDigitsByDefault() {
        assertThatCode(() -> policy.check("1234", List.of(), scope)).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("123456", List.of(), scope)).doesNotThrowAnyException();
        refused("m1.user.pin_length", "123", List.of());
        refused("m1.user.pin_length", "1234567", List.of());
    }

    @Test
    void digitsOnlyAndPresent() {
        refused("m1.user.pin_required", null, List.of());
        refused("m1.user.pin_required", "", List.of());
        refused("m1.user.pin_digits_only", "12 4", List.of());
        refused("m1.user.pin_digits_only", "abcd", List.of());
    }

    @Test
    void theEntityNarrowsButNeverWidensTheLength() {
        register.put(PinPolicy.LENGTH_MIN, "5");
        refused("m1.user.pin_length", "1234", List.of());
        assertThatCode(() -> policy.check("12345", List.of(), scope)).doesNotThrowAnyException();

        register.put(PinPolicy.LENGTH_MIN, "2");
        register.put(PinPolicy.LENGTH_MAX, "9");
        refused("m1.user.pin_length", "123", List.of());
        refused("m1.user.pin_length", "1234567", List.of());
    }

    @Test
    void aMisconfiguredRangeFallsBackToFourToSix() {
        register.put(PinPolicy.LENGTH_MIN, "6");
        register.put(PinPolicy.LENGTH_MAX, "4");
        assertThatCode(() -> policy.check("12345", List.of(), scope)).doesNotThrowAnyException();
    }

    @Test
    void noneOfTheLastThreeAgain() {
        List<String> recent = List.of("h:1111", "h:2222", "h:3333", "h:4444");
        refused("m1.user.pin_reused", "1111", recent);
        refused("m1.user.pin_reused", "3333", recent);
        assertThatCode(() -> policy.check("4444", recent, scope)).doesNotThrowAnyException();

        register.put(PinPolicy.HISTORY_DEPTH, "4");
        refused("m1.user.pin_reused", "4444", recent);
    }

    @Test
    void theProblemNeverCarriesThePin() {
        assertThatThrownBy(() -> policy.check("98765432", List.of(), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.parameters()).containsEntry("min", 4).containsEntry("max", 6);
                    assertThat(e.parameters().toString()).doesNotContain("98765432");
                    assertThat(e.getMessage()).doesNotContain("98765432");
                });
    }

    private void refused(String messageId, String pin, List<String> recent) {
        assertThatThrownBy(() -> policy.check(pin, recent, scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo(messageId));
    }

    private record MapRegistry(Map<String, String> values) implements ConfigRegistry {
        @Override
        public Optional<String> get(String key, ScopeContext scope) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, ConfigScope scope, String value, ScopeContext ctx, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class PlainHasher implements PinHasher {
        @Override
        public String hash(CharSequence pin) {
            return "h:" + pin;
        }

        @Override
        public boolean matches(CharSequence pin, String hash) {
            return hash.equals("h:" + pin);
        }
    }
}
