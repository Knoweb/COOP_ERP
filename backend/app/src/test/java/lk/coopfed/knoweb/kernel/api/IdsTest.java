package lk.coopfed.knoweb.kernel.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doc 18 P2: keys are UUID version 7, time-ordered, safe to generate on disconnected devices. */
class IdsTest {

    @Test
    void producesVersion7Variant2Ids() {
        UUID id = Ids.next();

        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void idsAreOrderedByCreationTime() throws InterruptedException {
        UUID first = Ids.next();
        Thread.sleep(3);
        UUID second = Ids.next();

        long firstMillis = first.getMostSignificantBits() >>> 16;
        long secondMillis = second.getMostSignificantBits() >>> 16;

        assertTrue(firstMillis < secondMillis, "the 48-bit timestamp prefix must grow with time");
        assertTrue(first.compareTo(second) < 0);
    }

    @Test
    void idsDoNotCollide() {
        Set<UUID> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(Ids.next()), "duplicate id generated");
        }
    }
}
