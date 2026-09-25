package lk.coopfed.knoweb.m1party.internal.grant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.BiFunction;
import lk.coopfed.knoweb.kernel.api.ProblemException;

/**
 * The window of an external grant (doc 21 section 4.6: "valid_until at most 12 months"), as a
 * pure function so that every case has a unit test.
 *
 * <p>A grant cannot start in the past: nothing it would have admitted then can be taken back, and
 * a start in the past would only stretch the twelve months backwards. A missing or past
 * {@code validFrom} is therefore now. A start in the future is allowed (an inspection booked for
 * next week).
 */
final class GrantWindow {

    /**
     * The hard maximum of doc 21 DR-4, which the table's CHECK constraint also holds (m1security
     * V0001); the configuration item may shorten it, never lengthen it. Assumes doc 10 L-07.
     */
    static final int MAX_MONTHS = 12;

    record Window(Instant validFrom, Instant validUntil) {}

    private GrantWindow() {}

    /**
     * @param maxMonths the configured maximum ({@code m1.external_grant.max_months}), held to 1..12
     * @param addMonths calendar months added to an instant exactly as the table's CHECK constraint
     *     adds them ({@code valid_from + interval '12 months'}, in the session's time zone): the
     *     handler asks the database, so that a grant this guard admits is never refused by the
     *     constraint on the last day of February
     * @throws ProblemException {@code m1.grant.window_invalid} when the grant would end at or
     *     before it starts (which includes an end that has already passed), {@code
     *     m1.grant.window_too_long} when it ends later than the maximum after its start
     */
    static Window of(
            Instant requestedFrom,
            Instant validUntil,
            Instant now,
            int maxMonths,
            BiFunction<Instant, Integer, Instant> addMonths) {

        Instant from = requestedFrom == null || requestedFrom.isBefore(now) ? now : requestedFrom;
        from = from.truncatedTo(ChronoUnit.MICROS);

        if (validUntil == null) {
            throw new ProblemException("m1.grant.window_invalid", Map.of("validFrom", from));
        }
        Instant until = validUntil.truncatedTo(ChronoUnit.MICROS);

        if (!until.isAfter(from)) {
            throw new ProblemException("m1.grant.window_invalid", Map.of("validFrom", from, "validUntil", until));
        }

        int months = Math.max(1, Math.min(MAX_MONTHS, maxMonths));
        Instant latest = addMonths.apply(from, months);

        if (until.isAfter(latest)) {
            throw new ProblemException(
                    "m1.grant.window_too_long", Map.of("maxMonths", months, "latestValidUntil", latest));
        }

        return new Window(from, until);
    }
}
