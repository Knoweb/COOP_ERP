package lk.coopfed.knoweb.kernel.internal.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The request hash of the idempotency store is keyed with a server secret, so that a stored
 * hash of a body carrying a PIN gives the PIN back to nobody; and the secret has no default
 * outside development.
 */
class IdempotencyFilterTest {

    private static final String PREFIX = "POST /v1/security/users/pin\n";
    private static final byte[] PIN_RESET = "{\"pin\":\"4821\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theHashIsKeyedSoAPlainDigestOfTheBodyDoesNotMatchIt() throws Exception {
        byte[] secret = "server-secret".getBytes(StandardCharsets.UTF_8);

        String keyed = IdempotencyFilter.hmac(secret, PREFIX, PIN_RESET);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(PREFIX.getBytes(StandardCharsets.UTF_8));
        String plain = HexFormat.of().formatHex(digest.digest(PIN_RESET));

        assertThat(keyed).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(plain);
    }

    @Test
    void theSameRequestUnderTheSameSecretHashesTheSameAndAnotherSecretDiffers() {
        byte[] secret = "server-secret".getBytes(StandardCharsets.UTF_8);
        byte[] other = "another-secret".getBytes(StandardCharsets.UTF_8);

        assertThat(IdempotencyFilter.hmac(secret, PREFIX, PIN_RESET))
                .isEqualTo(IdempotencyFilter.hmac(secret, PREFIX, PIN_RESET))
                .isNotEqualTo(IdempotencyFilter.hmac(other, PREFIX, PIN_RESET))
                .isNotEqualTo(IdempotencyFilter.hmac(secret, "POST /v1/other\n", PIN_RESET));
    }

    @Test
    void withoutASecretOnlyADevelopmentIssuerStarts() {
        assertThat(IdempotencyFilter.secretOrDevelopment("configured", "https://login.coopfed.lk/realms/coop"))
                .isEqualTo("configured");
        assertThat(IdempotencyFilter.secretOrDevelopment("", "http://localhost:8085/realms/coop"))
                .isEqualTo(IdempotencyFilter.DEVELOPMENT_SECRET);
        assertThat(IdempotencyFilter.secretOrDevelopment(null, "http://provider.test/realms/coop"))
                .isEqualTo(IdempotencyFilter.DEVELOPMENT_SECRET);
        assertThatThrownBy(() -> IdempotencyFilter.secretOrDevelopment(" ", "https://login.coopfed.lk/realms/coop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coop-erp.idempotency.hash-secret");
    }
}
