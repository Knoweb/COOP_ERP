package lk.coopfed.knoweb.till.sync

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPOutputStream
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.port.SignatureVerifier
import lk.coopfed.knoweb.till.core.port.UnreadablePinHash
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
 * string, so central can raise them without a till release, but only within bounds (TWK-10): memory
 * 8 MB to 1 GB, iterations 1 to 100, parallelism 1 to 16. A string outside them, or one that cannot
 * be read, is [UnreadablePinHash], never a wrong PIN.
 */
object Argon2PinVerifier : PinVerifier {
    private val MEMORY_KB = 8 * 1024..1024 * 1024
    private val ITERATIONS = 1..100
    private val PARALLELISM = 1..16
    private val VERSIONS = setOf(0x10, 0x13)

    override fun verify(pin: String, encodedHash: String): Boolean {
        val parts = encodedHash.split('$')
        // ["", "argon2id", "v=19", "m=65536,t=3,p=2", salt, hash]
        if (parts.size != 6 || parts[0].isNotEmpty() || parts[1] != "argon2id") throw UnreadablePinHash("not an Argon2id PHC string")
        val version = parts[2].removePrefix("v=").toIntOrNull()?.takeIf { it in VERSIONS }
            ?: throw UnreadablePinHash("an unknown Argon2 version")
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=', "").toIntOrNull() }
        fun param(name: String, bounds: IntRange): Int =
            params[name]?.takeIf { it in bounds } ?: throw UnreadablePinHash("Argon2 parameter $name missing or outside $bounds")
        val memory = param("m", MEMORY_KB)
        val iterations = param("t", ITERATIONS)
        val parallelism = param("p", PARALLELISM)
        val decoder = Base64.getDecoder()
        val salt: ByteArray
        val expected: ByteArray
        try {
            salt = decoder.decode(pad(parts[4]))
            expected = decoder.decode(pad(parts[5]))
        } catch (e: IllegalArgumentException) {
            throw UnreadablePinHash("the salt or hash is not base64")
        }
        if (salt.size < 8 || expected.size !in 16..64) throw UnreadablePinHash("a salt or hash of an unexpected length")
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(version)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build(),
        )
        val actual = ByteArray(expected.size)
        generator.generateBytes(pin.toByteArray(Charsets.UTF_8), actual)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pad(text: String) = text + "=".repeat((4 - text.length % 4) % 4)
}

/** GZIP for the batch body (doc 32 section 3.2), with the JDK's Deflater; Android has the same class. */
object JvmGzip : (ByteArray) -> ByteArray {
    override fun invoke(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
