package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The recipient hash key (wave 2, TWK-20 and M9-07): no default outside development. */
class RecipientHashTest {

    private static final String PRODUCTION_ISSUER = "https://login.coopfed.lk/realms/coop";

    @Test
    void withoutAKeyTheApplicationDoesNotStartOutsideDevelopment() {
        assertThatThrownBy(() -> new RecipientHash("", PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RecipientHash.PROPERTY);
    }

    @Test
    void aDevelopmentStackStartsWithTheDevelopmentKey() {
        RecipientHash development = new RecipientHash("", "http://localhost:8085/realms/coop");
        assertThat(development.of("SMS", " 0771234567 ")).isEqualTo(development.of("SMS", "0771234567"));
        assertThat(development.of("SMS", "0771234567")).isNotEqualTo(development.of("EMAIL", "0771234567"));
    }

    @Test
    void aConfiguredKeyMakesOtherHashesAndAnotherKeyId() {
        // Obviously not a secret: 32 bytes of 0x07.
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) 7);
        RecipientHash configured = new RecipientHash(Base64.getEncoder().encodeToString(bytes), PRODUCTION_ISSUER);
        RecipientHash development = new RecipientHash("", "http://localhost:8085/realms/coop");

        assertThat(configured.of("SMS", "0771234567")).isNotEqualTo(development.of("SMS", "0771234567"));
        assertThat(configured.keyId()).isNotEqualTo(development.keyId());
    }
}
