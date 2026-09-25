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
 * instance must verify against the key another instance handed out. When they are not set (a
 * developer machine, a test) the instance makes a key of its own and says so in the log; tills
 * enrolled against it would not verify another instance's signature.
 */
@Component
class TillSigner {

    static final String ALGORITHM = "Ed25519";

    private static final Logger log = LoggerFactory.getLogger(TillSigner.class);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    TillSigner(
            @Value("${coop-erp.sync.signing.private-key:}") String privateKeyBase64,
            @Value("${coop-erp.sync.signing.public-key:}") String publicKeyBase64) {
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            if (privateKeyBase64.isBlank() || publicKeyBase64.isBlank()) {
                KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
                this.privateKey = pair.getPrivate();
                this.publicKey = pair.getPublic();
                log.warn("No till signing key is configured (coop-erp.sync.signing.*): this instance signs with a"
                        + " key of its own, which no other instance shares. Configure one outside development.");
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
