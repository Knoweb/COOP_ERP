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

/**
 * A PIN hash the till cannot read (a malformed PHC string, parameters out of bounds): central's or
 * the snapshot's fault, not the cashier's, so it never counts as a wrong PIN (TWK-10).
 */
class UnreadablePinHash(message: String) : Exception(message)

/** The operator's PIN against the Argon2id hash in the snapshot (19A: m=64 MB, t=3, p=2, PHC string). */
fun interface PinVerifier {
    /**
     * @return whether [pin] matches
     * @throws UnreadablePinHash when [encodedHash] cannot be read or its parameters are out of bounds
     */
    fun verify(pin: String, encodedHash: String): Boolean
}

/** The till's clock and the shop's time zone. */
interface TillClock {
    fun now(): Instant
    val zone: TimeZone

    /**
     * Milliseconds on a clock that only moves forward at the rate of real time and that nobody can
     * set (System.nanoTime on the JVM, SystemClock.elapsedRealtime on Android), from any start in
     * this process; null when the platform has none. It tells a hand-set wall clock from time that
     * really passed (doc 26 section 3.10, "device clock plus monotonic uptime").
     */
    fun elapsedMillis(): Long? = null
}

/**
 * Keeps the database key off the disk in clear (research report section 6): DPAPI on Windows, a
 * file readable only by the till's user on Linux, the Android Keystore on Android.
 */
interface KeyVault {
    /**
     * The key that opens the local database, created on first use. A vault never makes a new key
     * when the database already exists (the new key could not open it): it refuses instead.
     */
    fun databaseKey(): String
}
