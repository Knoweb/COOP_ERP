package lk.coopfed.knoweb.kernel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lk.coopfed.knoweb.kernel.internal.security.JwtClaimsMapper;
import org.junit.jupiter.api.Test;

/**
 * The one rule for a key that is not set, and the keyed hash itself (wave 2, 6 October 2026:
 * {@code docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md} (1)).
 */
class KeyedHashTest {

    private static final String PRODUCTION_ISSUER = "https://login.coopfed.lk/realms/coop";
    private static final String DEVELOPMENT_ISSUER = "http://localhost:8085/realms/coop";

    /** Obviously not a secret: 32 bytes of 0x01 and of 0x02. */
    private static final String KEY_ONE = Base64.getEncoder().encodeToString(filled(1));

    private static final String KEY_TWO = Base64.getEncoder().encodeToString(filled(2));

    @Test
    void aConfiguredKeyIsUsedEverywhere() {
        KeyedHash hash = KeyedHash.fromProperty("test.key", KEY_ONE, PRODUCTION_ISSUER, "seed");

        assertThat(hash.hex("SMS:0771234567")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash.hex("SMS:0771234567")).isEqualTo(hash.hex("SMS:0771234567"));
        assertThat(hash.keyId()).hasSize(16);
    }

    @Test
    void theHashIsKeyedSoAPlainDigestOfTheValueIsNotIt() throws Exception {
        KeyedHash one = KeyedHash.fromProperty("test.key", KEY_ONE, PRODUCTION_ISSUER, "seed");
        KeyedHash two = KeyedHash.fromProperty("test.key", KEY_TWO, PRODUCTION_ISSUER, "seed");
        String plain = HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256").digest("SMS:0771234567".getBytes(StandardCharsets.UTF_8)));

        assertThat(one.hex("SMS:0771234567"))
                .isNotEqualTo(two.hex("SMS:0771234567"))
                .isNotEqualTo(plain);
        assertThat(one.keyId()).isNotEqualTo(two.keyId());
    }

    @Test
    void withNoKeyADevelopmentStackUsesTheDevelopmentKeyAndAnythingElseDoesNotStart() {
        KeyedHash development = KeyedHash.fromProperty("test.key", "", DEVELOPMENT_ISSUER, "seed");
        assertThat(development.hex("x"))
                .isEqualTo(KeyedHash.fromProperty("test.key", null, "http://provider.test/realms/coop", "seed")
                        .hex("x"));

        assertThatThrownBy(() -> KeyedHash.fromProperty("test.key", " ", PRODUCTION_ISSUER, "seed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.key")
                .hasMessageContaining("outside development");
        assertThatThrownBy(() -> KeyedHash.fromProperty("test.key", "", null, "seed"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyThatIsNotThirtyTwoBytesOfBase64IsRefusedWithoutQuotingIt() {
        String notBase64 = "not*base64*at*all";
        assertThatThrownBy(() -> KeyedHash.fromProperty("test.key", notBase64, PRODUCTION_ISSUER, "seed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.key")
                .hasMessageNotContaining(notBase64)
                .hasNoCause();
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> KeyedHash.fromProperty("test.key", tooShort, PRODUCTION_ISSUER, "seed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(tooShort);
    }

    @Test
    void theKeyIsNeverShown() {
        KeyedHash hash = KeyedHash.fromProperty("test.key", KEY_ONE, PRODUCTION_ISSUER, "seed");
        assertThat(hash.toString()).doesNotContain(KEY_ONE).contains(hash.keyId());
    }

    @Test
    void theSecretRuleReturnsTheConfiguredValueOrTheDevelopmentOne() {
        assertThat(KeyedHash.configuredOrDevelopment("p", "set", PRODUCTION_ISSUER, "dev"))
                .isEqualTo("set");
        assertThat(KeyedHash.configuredOrDevelopment("p", "", DEVELOPMENT_ISSUER, "dev"))
                .isEqualTo("dev");
        assertThatThrownBy(() -> KeyedHash.configuredOrDevelopment("p", null, PRODUCTION_ISSUER, "dev"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDevelopmentIssuerIsTheSameOneTheTokenMapperKnows() {
        for (String issuer : List.of(
                "http://localhost:8085/realms/coop",
                "http://127.0.0.1:8085/realms/coop",
                "http://[::1]:8085/realms/coop",
                "http://keycloak.localhost/realms/coop",
                "http://provider.test/realms/coop",
                "https://login.coopfed.lk/realms/coop",
                "https://localhost.coopfed.lk/realms/coop",
                "not a uri",
                "")) {
            assertThat(KeyedHash.isDevelopmentIssuer(issuer))
                    .as(issuer)
                    .isEqualTo(JwtClaimsMapper.isDevelopmentIssuer(issuer));
        }
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[KeyedHash.KEY_BYTES];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
