package lk.coopfed.knoweb.kernel.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The business date of a location (doc 19 section 13; 19A section 13).
 *
 * <p>A business date is not the calendar date. It belongs to a location and moves when that
 * location closes its day: a shop that closes at 01:30 is still trading on yesterday's
 * business date at 00:45. {@code document.business_date} is set from here at issuance, or
 * taken from the till's own value on ingestion, and never from the wall clock.
 *
 * <p>The argument is the location, not the caller's scope: an accountant working entity-wide
 * has no location in the scope and still posts for one particular shop.
 *
 * <p>17A stub: the calendar date in the business time zone, the same for every location.
 * 19A K-13 replaces it with the location's day-close state; the interface does not change.
 */
public interface BusinessDate {

    /**
     * @param locationId the location the document or movement belongs to; never null
     */
    LocalDate current(UUID locationId);
}
