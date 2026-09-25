package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The PIN hash of 19A section 2: Argon2id with the fixed parameters, salted, verifiable. */
class Argon2PinHasherTest {

    private final Argon2PinHasher hasher = new Argon2PinHasher();

    @Test
    void theHashCarriesTheAlgorithmAndTheParametersOf19A() {
        String hash = hasher.hash("4821");

        // PHC string: the device reads the parameters from the hash itself.
        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=2$");
        assertThat(hasher.matches("4821", hash)).isTrue();
        assertThat(hasher.matches("4822", hash)).isFalse();
    }

    @Test
    void theSamePinHashesDifferentlyEveryTime() {
        assertThat(hasher.hash("4821")).isNotEqualTo(hasher.hash("4821"));
    }

    @Test
    void aMalformedHashOrAnEmptyPinNeverMatches() {
        assertThat(hasher.matches("4821", "not-a-hash")).isFalse();
        assertThat(hasher.matches("4821", "")).isFalse();
        assertThat(hasher.matches("", hasher.hash("4821"))).isFalse();
        assertThatThrownBy(() -> hasher.hash("")).isInstanceOf(IllegalArgumentException.class);
    }
}
