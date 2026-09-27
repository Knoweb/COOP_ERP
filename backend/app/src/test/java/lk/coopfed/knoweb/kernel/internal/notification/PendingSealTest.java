package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a notification retry needs is sealed with a key the database never sees (CR-19A-8): a
 * round trip opens, the ciphertext does not carry the plaintext, a value moved to another row or
 * changed does not open, a key change keeps the rows sealed under the previous key readable,
 * and the key has no default outside development.
 */
class PendingSealTest {

    private static final String PRODUCTION_ISSUER = "https://login.coopfed.lk/realms/coop";
    private static final String KEY_A = Base64.getEncoder().encodeToString(bytes(32, (byte) 1));
    private static final String KEY_B = Base64.getEncoder().encodeToString(bytes(32, (byte) 2));
    private static final byte[] HELD = "{\"recipient\":\"0771234567\",\"arguments\":{\"temporaryPassword\":\"Xy7\"}}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void aSealedValueOpensForItsOwnRowAndDoesNotCarryThePlaintext() {
        PendingSeal seal = new PendingSeal(KEY_A, "", PRODUCTION_ISSUER);
        UUID id = UUID.randomUUID();

        PendingSeal.Sealed sealed = seal.seal(id, HELD);

        assertThat(new String(sealed.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("0771234567");
        assertThat(sealed.keyId()).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(seal.open(id, sealed.keyId(), sealed.bytes())).hasValue(HELD);
        // A fresh nonce every time: the same plaintext never seals to the same bytes.
        assertThat(seal.seal(id, HELD).bytes()).isNotEqualTo(sealed.bytes());
    }

    @Test
    void aValueMovedToAnotherRowOrChangedDoesNotOpen() {
        PendingSeal seal = new PendingSeal(KEY_A, "", PRODUCTION_ISSUER);
        UUID id = UUID.randomUUID();
        PendingSeal.Sealed sealed = seal.seal(id, HELD);

        assertThat(seal.open(UUID.randomUUID(), sealed.keyId(), sealed.bytes())).isEmpty();

        byte[] changed = sealed.bytes().clone();
        changed[changed.length - 1] ^= 1;
        assertThat(seal.open(id, sealed.keyId(), changed)).isEmpty();
        assertThat(seal.open(id, sealed.keyId(), new byte[4])).isEmpty();
        assertThat(seal.open(id, "0000000000000000", sealed.bytes())).isEmpty();
        assertThat(seal.open(id, null, sealed.bytes())).isEmpty();
    }

    @Test
    void afterAKeyChangeTheRowsOfThePreviousKeyStillOpenAndAnUnknownKeyOpensNothing() {
        PendingSeal before = new PendingSeal(KEY_A, "", PRODUCTION_ISSUER);
        UUID id = UUID.randomUUID();
        PendingSeal.Sealed old = before.seal(id, HELD);

        PendingSeal rotated = new PendingSeal(KEY_B, KEY_A, PRODUCTION_ISSUER);
        assertThat(rotated.open(id, old.keyId(), old.bytes())).hasValue(HELD);
        assertThat(rotated.seal(id, HELD).keyId()).isNotEqualTo(old.keyId());

        PendingSeal forgotten = new PendingSeal(KEY_B, "", PRODUCTION_ISSUER);
        assertThat(forgotten.open(id, old.keyId(), old.bytes())).isEmpty();
    }

    @Test
    void withoutAKeyOnlyADevelopmentIssuerStartsAndAKeyMustBeThirtyTwoBytes() {
        assertThat(PendingSeal.keyOrDevelopment("", "http://localhost:8085/realms/coop"))
                .hasSize(32);
        assertThat(PendingSeal.keyOrDevelopment(null, "http://provider.test/realms/coop"))
                .hasSize(32);
        assertThatThrownBy(() -> PendingSeal.keyOrDevelopment(" ", PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coop-erp.notification.pending-key");
        assertThatThrownBy(() -> PendingSeal.keyOrDevelopment(
                        Base64.getEncoder().encodeToString(bytes(16, (byte) 3)), PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 random bytes");
        assertThatThrownBy(() -> PendingSeal.keyOrDevelopment("not base64 !", PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> new PendingSeal(KEY_A, "short", PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending-key-previous");
    }

    private static byte[] bytes(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
