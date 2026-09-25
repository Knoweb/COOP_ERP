package lk.coopfed.knoweb.kernel.api;

/**
 * Hashes a till operator's PIN for the operator snapshot a device caches (doc 19 section 2,
 * N-03; 19A section 2). The hash is what the snapshot carries; the device verifies the PIN
 * against it, and the kernel never sees a PIN at login.
 *
 * <p>The algorithm and its parameters are fixed by 19A: Argon2id, 64 MB of memory, three
 * iterations, parallelism two, a 16-byte salt, a 32-byte output. The hash is a PHC string
 * ({@code $argon2id$v=19$m=65536,t=3,p=2$...}), so the device reads the parameters from the
 * hash itself and a later change of parameters verifies old hashes still.
 *
 * <p>A PIN is a low-entropy credential: never log one, never log a hash (AGENTS.md).
 */
public interface PinHasher {

    /** A fresh salt every time: the same PIN gives a different hash on every call. */
    String hash(CharSequence pin);

    /** Constant-time; false for a malformed hash rather than an exception. */
    boolean matches(CharSequence pin, String hash);
}
