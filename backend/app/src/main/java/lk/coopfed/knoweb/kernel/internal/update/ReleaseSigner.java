package lk.coopfed.knoweb.kernel.internal.update;

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
 * Signs and verifies application update instructions and manifests (doc 31 section 4, 5.1 & 8).
 * Ed25519 keys and signatures.
 */
@Component
public class ReleaseSigner {

    static final String ALGORITHM = "Ed25519";

    private static final Logger log = LoggerFactory.getLogger(ReleaseSigner.class);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    @Autowired
    public ReleaseSigner(
            @Value("${coop-erp.release.signing.private-key:}") String privateKeyBase64,
            @Value("${coop-erp.release.signing.public-key:}") String publicKeyBase64,
            @Value("${coop-erp.release.signing.required:false}") boolean required) {
        boolean blank = privateKeyBase64 == null
                || privateKeyBase64.isBlank()
                || publicKeyBase64 == null
                || publicKeyBase64.isBlank();
        if (blank && required) {
            throw new IllegalStateException(
                    "No release signing key is configured: set coop-erp.release.signing.private-key"
                            + " and coop-erp.release.signing.public-key");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            if (blank) {
                KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
                this.privateKey = pair.getPrivate();
                this.publicKey = pair.getPublic();
                log.warn("No release signing key is configured: this instance signs with an ephemeral key pair.");
            } else {
                this.privateKey = factory.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip())));
                this.publicKey = factory.generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.strip())));
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            this.keyId = HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The release signing key could not be loaded", e);
        }
    }

    /** Direct constructor for unit testing. */
    public ReleaseSigner(String privateKeyBase64, String publicKeyBase64) {
        this(privateKeyBase64, publicKeyBase64, false);
    }

    /** Base64 of the Ed25519 signature over UTF-8 bytes of text. */
    public String sign(String text) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    /** Verifies a Base64 Ed25519 signature over UTF-8 bytes of text. */
    public boolean verify(String text, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(text.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64.strip()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public String keyId() {
        return keyId;
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
