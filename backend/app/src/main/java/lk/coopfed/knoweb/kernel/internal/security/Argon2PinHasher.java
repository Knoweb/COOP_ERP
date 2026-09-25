package lk.coopfed.knoweb.kernel.internal.security;

import lk.coopfed.knoweb.kernel.api.PinHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * {@link PinHasher} with the parameters 19A section 2 fixes: Argon2id, 16-byte salt, 32-byte
 * hash, parallelism 2, 64 MB (65536 KiB), 3 iterations. Spring Security's encoder writes and
 * reads the PHC string; Bouncy Castle does the arithmetic.
 */
@Component
public class Argon2PinHasher implements PinHasher {

    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;
    static final int PARALLELISM = 2;
    static final int MEMORY_KIB = 64 * 1024;
    static final int ITERATIONS = 3;

    private final Argon2PasswordEncoder encoder =
            new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

    @Override
    public String hash(CharSequence pin) {
        if (pin == null || pin.isEmpty()) {
            throw new IllegalArgumentException("A PIN is never empty");
        }
        return encoder.encode(pin);
    }

    @Override
    public boolean matches(CharSequence pin, String hash) {
        if (pin == null || pin.isEmpty() || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(pin, hash);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
