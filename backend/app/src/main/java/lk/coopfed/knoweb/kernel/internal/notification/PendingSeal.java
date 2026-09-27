package lk.coopfed.knoweb.kernel.internal.notification;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lk.coopfed.knoweb.kernel.internal.security.JwtClaimsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seals what a retry needs (the recipient and the placeholders of a QUEUED notification) before
 * it is written to {@code kernel.notification_pending}, and opens it again for the retry.
 *
 * <p>Why (decided 27 September 2026 on the architect's delegation, CR-19A-8): doc 19 section 7
 * says phone numbers are hashed in the log, and the placeholders of a direct send can be a
 * temporary password (M1's {@code TemporaryPasswordDelivery}). Held in clear, both would sit in
 * the database and its backups for up to 24 hours. AES-256-GCM in the application, with a key
 * the database never sees: a copy of the table, a backup or a reader with the right scope gets
 * ciphertext only. pgcrypto was rejected because the key would travel in the SQL text, where
 * the server's statement log and {@code pg_stat_statements} can keep it.
 *
 * <p>The key is {@code coop-erp.notification.pending-key} (base64 of 32 random bytes), the same
 * on every instance, since any instance retries. It has no default outside development (an
 * issuer on localhost or a .test host, as {@link JwtClaimsMapper#isDevelopmentIssuer}); there a
 * fixed development key is used, with a warning. To change the key, set the old one as
 * {@code pending-key-previous} for a day: a row lives at most 24 hours, so after that nothing
 * sealed under the old key is left. A row whose key is unknown opens to nothing, and the
 * notification is then failed like one whose hold was cleared ("nothing held for the retry").
 *
 * <p>Every row gets a fresh 12-byte nonce, and the notification id is the associated data, so a
 * sealed value copied onto another row does not open there.
 */
@Component
class PendingSeal {

    private static final Logger log = LoggerFactory.getLogger(PendingSeal.class);

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Development only: the text the development key is derived from when nothing is configured. */
    static final String DEVELOPMENT_KEY_SEED = "coop-erp-notification-pending-dev";

    /** What a row stores: which key sealed it, and the nonce followed by the ciphertext. */
    record Sealed(String keyId, byte[] bytes) {}

    private final SecureRandom random = new SecureRandom();
    private final byte[] currentKey;
    private final String currentKeyId;
    private final Map<String, byte[]> keys = new LinkedHashMap<>();

    PendingSeal(
            @Value("${coop-erp.notification.pending-key:}") String key,
            @Value("${coop-erp.notification.pending-key-previous:}") String previousKey,
            @Value("${coop-erp.security.oidc.issuer:}") String issuer) {
        this.currentKey = keyOrDevelopment(key, issuer);
        this.currentKeyId = keyId(currentKey);
        keys.put(currentKeyId, currentKey);
        if (previousKey != null && !previousKey.isBlank()) {
            byte[] previous = decode(previousKey, "coop-erp.notification.pending-key-previous");
            keys.putIfAbsent(keyId(previous), previous);
        }
    }

    /** The configured key; on a development stack a fixed one with a warning; else the start fails. */
    static byte[] keyOrDevelopment(String configured, String issuer) {
        if (configured != null && !configured.isBlank()) {
            return decode(configured, "coop-erp.notification.pending-key");
        }
        if (JwtClaimsMapper.isDevelopmentIssuer(issuer)) {
            log.warn("coop-erp.notification.pending-key is not set; using the development key"
                    + " (the issuer is a development one). Set COOP_ERP_NOTIFICATION_KEY outside development.");
            return sha256(DEVELOPMENT_KEY_SEED.getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("coop-erp.notification.pending-key must be set outside development:"
                + " the recipient and the placeholders of a queued notification are sealed with it");
    }

    private static byte[] decode(String base64, String property) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " must be base64 of " + KEY_BYTES + " random bytes", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    property + " must be base64 of " + KEY_BYTES + " random bytes, not " + key.length);
        }
        return key;
    }

    /** A short public name of a key: which one sealed a row, without saying anything about it. */
    static String keyId(byte[] key) {
        byte[] prefix = "coop-erp.notification.key-id:".getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, input, prefix.length, key.length);
        return HexFormat.of().formatHex(sha256(input), 0, 8);
    }

    Sealed seal(UUID notificationId, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.ENCRYPT_MODE, new SecretKeySpec(currentKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(notificationId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new Sealed(
                    currentKeyId,
                    ByteBuffer.allocate(nonce.length + ciphertext.length)
                            .put(nonce)
                            .put(ciphertext)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is part of every JDK", e);
        }
    }

    /** The plaintext, or empty when the key is not known here or the value was changed or moved. */
    Optional<byte[]> open(UUID notificationId, String keyId, byte[] sealed) {
        byte[] key = keyId == null ? null : keys.get(keyId);
        if (key == null || sealed == null || sealed.length <= NONCE_BYTES) {
            log.warn("Held notification {} cannot be opened: its key {} is not configured", notificationId, keyId);
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData(notificationId));
            return Optional.of(cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES));
        } catch (GeneralSecurityException e) {
            log.warn("Held notification {} does not open under key {}", notificationId, keyId);
            return Optional.empty();
        }
    }

    private static byte[] associatedData(UUID notificationId) {
        return notificationId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
