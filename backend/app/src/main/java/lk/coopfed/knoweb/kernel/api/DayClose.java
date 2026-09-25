package lk.coopfed.knoweb.kernel.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Closes a location's business day (doc 19 section 9; 19A sections 12 and 13). A business
 * date is not the calendar date: it belongs to the location and moves only here, once per
 * calendar day at most, when the location's last till session closes (the day-close trigger
 * on {@code till_session.closed.v1}) or at the configured cut-off. What a close does: the
 * location's business date advances, {@code DAY_CLOSED} is recorded, and
 * {@code location.day_closed.v1} is published, which end-of-day aggregation and
 * {@code apply_from} activation consume.
 *
 * <p>Idempotent within a calendar day: a second close on the same day changes nothing and
 * returns the current date.
 */
public interface DayClose {

    /**
     * @param locationId the location; its business date is the caller's scope entity's to close
     * @return the business date the location trades on from now
     */
    LocalDate close(UUID locationId, ScopeContext ctx);
}
