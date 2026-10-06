package lk.coopfed.knoweb.kernel.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A keyed hash (HMAC-SHA-256, FIPS 198-1) of a low-entropy value the system must compare but
 * never read back: a notification's recipient (a phone number), a customer's NIC. A plain
 * SHA-256 of a ten-digit phone number is reversed by trying every number in seconds; under a key
 * the database never sees, the stored value tells nothing to whoever holds the table or a backup.
 *
 * <p>Why one facility (decided 6 October 2026 on the architect's delegation,
 * {@code docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md}): the rule for a missing key
 * is the same for every key of the application, and was written once in {@code IdempotencyFilter}
 * and again in {@code PendingSeal}. It lives here so the keys cannot drift: a key is read from an
 * application property (the environment or a secret store, never a {@code ConfigRegistry} item,
 * which would live in the database the key protects); with no key, a development stack (an issuer
 * on localhost, a {@code .localhost} or a {@code .test} host) uses a fixed development key with a
 * warning, and anything else refuses to start. Each purpose has its own key (NIST SP 800-57 key
 * separation): a leak of one opens nothing else.
 *
 * <p>The key itself is never logged and never part of a message: an error names the property,
 * and {@link #keyId()} is a short public name of the key, stored beside every hash so that a
 * second key can be introduced one day.
 */
public final class KeyedHash {

    private static final Logger log = LoggerFactory.getLogger(KeyedHash.class);

    private static final String HMAC = "HmacSHA256";

    /** The length of a key: 32 random bytes, given as base64. */
    public static final int KEY_BYTES = 32;

    private final byte[] key;
    private final String keyId;

    private KeyedHash(byte[] key) {
        this.key = key.clone();
        this.keyId = keyIdOf(key);
    }

    /**
     * The keyed hash of a property: the configured key (base64 of 32 random bytes); on a
     * development stack, a fixed key derived from {@code developmentSeed}, with a warning; else
     * the start fails.
     *
     * @param property        the property's name, for the messages (never its value)
     * @param configured      the property's value, blank when not set
     * @param issuer          {@code coop-erp.security.oidc.issuer}, which says whether this is a development stack
     * @param developmentSeed what the development key is derived from; not a secret
     */
    public static KeyedHash fromProperty(String property, String configured, String issuer, String developmentSeed) {
        if (configured != null && !configured.isBlank()) {
            return new KeyedHash(decode(property, configured));
        }
        configuredOrDevelopment(property, configured, issuer, developmentSeed);
        return new KeyedHash(sha256(developmentSeed.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The one rule for a secret that is not set: the configured value; on a development stack
     * the development value, with a warning that names the property; anywhere else an
     * {@link IllegalStateException}, so the application does not start.
     */
    public static String configuredOrDevelopment(
            String property, String configured, String issuer, String developmentValue) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (isDevelopmentIssuer(issuer)) {
            log.warn(
                    "{} is not set; using the development value (the issuer is a development one)."
                            + " Set it outside development.",
                    property);
            return developmentValue;
        }
        throw new IllegalStateException(property + " must be set outside development");
    }

    /**
     * An issuer on this machine or on a development host name (localhost, *.localhost, *.test).
     * The same answer as {@code JwtClaimsMapper.isDevelopmentIssuer}; {@code KeyedHashTest} pins
     * the two together.
     */
    public static boolean isDevelopmentIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(issuer.trim()).getHost();
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("[::1]")
                || host.endsWith(".localhost")
                || host.endsWith(".test");
    }

    /** HMAC-SHA-256 of the UTF-8 text under the key, as 64 lower-case hex characters. */
    public String hex(String text) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }

    /** A short public name of the key (16 hex characters of a hash of it): which key made a stored hash. */
    public String keyId() {
        return keyId;
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "KeyedHash[keyId=" + keyId + "]";
    }

    private static byte[] decode(String property, String base64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            // Not the exception's own message: it can quote the offending input.
            throw new IllegalStateException(property + " must be base64 of " + KEY_BYTES + " random bytes");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                    property + " must be base64 of " + KEY_BYTES + " random bytes, not " + decoded.length);
        }
        return decoded;
    }

    private static String keyIdOf(byte[] key) {
        byte[] prefix = "coop-erp.keyed-hash.key-id:".getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, input, prefix.length, key.length);
        return HexFormat.of().formatHex(sha256(input), 0, 8);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
