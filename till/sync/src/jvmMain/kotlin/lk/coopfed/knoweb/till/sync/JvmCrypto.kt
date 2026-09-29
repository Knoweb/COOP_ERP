package lk.coopfed.knoweb.till.sync

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.port.SignatureVerifier
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Ed25519 with the JDK's own EdDSA (JDK 15+), as the backend's till simulator checks it. */
object JdkEd25519Verifier : SignatureVerifier {
    override fun verifyEd25519(publicKey: String, message: ByteArray, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}

/**
 * Checks a PIN against the PHC string central stores (19A: "$argon2id$v=19$m=65536,t=3,p=2$salt$hash",
 * Spring's Argon2PasswordEncoder format, base64 without padding). The parameters are read from the
 * string, so central can raise them without a till release.
 */
object Argon2PinVerifier : PinVerifier {
    override fun verify(pin: String, encodedHash: String): Boolean {
        val parts = encodedHash.split('$')
        // ["", "argon2id", "v=19", "m=65536,t=3,p=2", salt, hash]
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val version = parts[2].removePrefix("v=").toIntOrNull() ?: return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(pad(parts[4]))
        val expected = decoder.decode(pad(parts[5]))
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(version)
                .withMemoryAsKB(params.getValue("m"))
                .withIterations(params.getValue("t"))
                .withParallelism(params.getValue("p"))
                .withSalt(salt)
                .build(),
        )
        val actual = ByteArray(expected.size)
        generator.generateBytes(pin.toByteArray(Charsets.UTF_8), actual)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pad(text: String) = text + "=".repeat((4 - text.length % 4) % 4)
}
