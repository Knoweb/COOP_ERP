package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One gazetted control price (doc 23 section 3.4): a ceiling for a SKU from a date to a date (null:
 * open-ended). Rows are never removed, so the list of them is the history.
 */
public record ControlPriceView(
        UUID controlPriceId,
        UUID skuId,
        BigDecimal ceilingPrice,
        String ceilingUomCode,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String gazetteReference,
        UUID enteredBy,
        Instant enteredAt) {

    public boolean inForce(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}
