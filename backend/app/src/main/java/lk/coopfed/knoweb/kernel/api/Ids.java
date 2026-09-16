package lk.coopfed.knoweb.kernel.api;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID next() {
        long timestamp = System.currentTimeMillis();

        long mostSignificantBits = (timestamp & 0xFFFFFFFFFFFFL) << 16;

        // UUID version 7
        mostSignificantBits |= 0x7000L;

        // 12 random bits after the version
        mostSignificantBits |= RANDOM.nextInt(1 << 12);

        long leastSignificantBits = RANDOM.nextLong();

        // RFC UUID variant 10xx
        leastSignificantBits &= 0x3FFFFFFFFFFFFFFFL;
        leastSignificantBits |= 0x8000000000000000L;

        return new UUID(
                mostSignificantBits,
                leastSignificantBits);
    }
}