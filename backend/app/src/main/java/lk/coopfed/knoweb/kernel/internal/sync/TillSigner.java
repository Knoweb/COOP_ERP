package lk.coopfed.knoweb.kernel.internal.sync;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs what central sends a till that the till must be able to trust on its own (doc 32 section
 * 9; doc 19 section 3.3): the revoke instruction a suspended device receives, and in the second
 * part of K-08 the permission snapshot and the snapshot manifests (19A section 3 names this the
 * till snapshot signer). Ed25519: small keys and signatures, in the JDK by name (the till, whose
 * minimum Android version predates the platform's own Ed25519, verifies with a library); the till
 * keeps the public key from its enrolment answer.
 *
 * <p>The key pair is configuration, {@code coop-erp.sync.signing.private-key} (PKCS#8, base64)
 * and {@code .public-key} (X.509, base64), the same on every instance: a signature from one
 * instance must verify against the key another instance handed out.
 *
 * <p>Fail closed (wave 2, TWK-27, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-kernel-defaults-and-limits.md (1)): with
 * {@code coop-erp.sync.signing.required} true, the default, a blank key stops the start, because
 * two instances that each made a key of their own would each sign what the tills enrolled at the
 * other reject, and a restart would change the key for all of them. Only the tests set it false;
 * the instance then makes a key of its own and says so in the log.
 */
@Component
class TillSigner {

    static final String ALGORITHM = "Ed25519";

    private static final Logger log = LoggerFactory.getLogger(TillSigner.class);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    @Autowired
    TillSigner(
            @Value("${coop-erp.sync.signing.private-key:}") String privateKeyBase64,
            @Value("${coop-erp.sync.signing.public-key:}") String publicKeyBase64,
            @Value("${coop-erp.sync.signing.required:true}") boolean required) {
        boolean blank = privateKeyBase64 == null
                || privateKeyBase64.isBlank()
                || publicKeyBase64 == null
                || publicKeyBase64.isBlank();
        if (blank && required) {
            throw new IllegalStateException("No till signing key is configured: set coop-erp.sync.signing.private-key"
                    + " and coop-erp.sync.signing.public-key (SYNC_SIGNING_PRIVATE_KEY, SYNC_SIGNING_PUBLIC_KEY), the"
                    + " same on every instance; coop-erp.sync.signing.required=false is for the tests only");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            if (blank) {
                KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
                this.privateKey = pair.getPrivate();
                this.publicKey = pair.getPublic();
                log.warn("No till signing key is configured (coop-erp.sync.signing.*, required=false): this instance"
                        + " signs with a key of its own, which no other instance shares. For tests only.");
            } else {
                this.privateKey = factory.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip())));
                this.publicKey = factory.generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.strip())));
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            this.keyId = HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The till signing key could not be loaded", e);
        }
    }

    /** A signer for a unit test: a blank key makes one of its own. */
    TillSigner(String privateKeyBase64, String publicKeyBase64) {
        this(privateKeyBase64, publicKeyBase64, false);
    }

    /** Base64 of the Ed25519 signature over the UTF-8 bytes of {@code text}. */
    String sign(String text) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    String keyId() {
        return keyId;
    }

    /** The public key as the till receives it: X.509 SubjectPublicKeyInfo, base64. */
    String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
