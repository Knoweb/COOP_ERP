package lk.coopfed.knoweb.m3pricing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * What a query returns. Entities never leave the module (17A section 4.4): queries hand
 * out records like this one, which the caller cannot use to change anything.
 *
 * <p>{@code createdAt} is an {@link Instant}: a point in time, with no zone. It is turned into
 * Colombo wall-clock time on the screen and nowhere else.
 */
public record PriceListView(
        UUID id,
        UUID ownerEntityId,
        String textEn,
        String textSi,
        String textTa,
        String status,
        Instant createdAt) {
}
