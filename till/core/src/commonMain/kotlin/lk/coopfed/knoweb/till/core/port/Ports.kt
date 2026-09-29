package lk.coopfed.knoweb.till.core.port

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/*
 * The till's ports (research report 7A.1): everything business code needs from the outside world
 * is one of these interfaces, implemented per platform and wired at start-up. Business code never
 * branches on the platform.
 */

/** Ed25519 verification (the JDK on desktop; BouncyCastle or Conscrypt on Android). */
fun interface SignatureVerifier {
    /**
     * @param publicKey X.509 SubjectPublicKeyInfo, base64, as the enrolment answer gives it
     */
    fun verifyEd25519(publicKey: String, message: ByteArray, signature: ByteArray): Boolean
}

/** The operator's PIN against the Argon2id hash in the snapshot (19A: m=64 MB, t=3, p=2, PHC string). */
fun interface PinVerifier {
    fun verify(pin: String, encodedHash: String): Boolean
}

/** The till's clock and the shop's time zone. */
interface TillClock {
    fun now(): Instant
    val zone: TimeZone
}

/**
 * Keeps the database key off the disk in clear (research report section 6): DPAPI on Windows, a
 * file readable only by the till's user on Linux, the Android Keystore on Android.
 */
interface KeyVault {
    /** The key that opens the local database, created on first use. */
    fun databaseKey(): String
}
