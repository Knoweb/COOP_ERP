package lk.coopfed.archfixtures.m1party;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Violates the clock rule: reads the wall clock directly. The instant cannot be fixed in a test,
 * and the date is the one of whatever time zone the server happens to run in.
 */
public class ReadsTheWallClock {

    public Instant stamp() {
        return Instant.now();
    }

    public LocalDate today() {
        return LocalDate.now();
    }
}
