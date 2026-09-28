package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * EnterControlPrice (23A section 7; doc 23 section 4.3): a gazetted maximum price for a SKU from
 * a date. The Federation enters it with a fresh second factor; the ceiling of the same SKU in
 * force on that date is closed at {@code effectiveFrom - 1}.
 *
 * @param ceilingPrice     the maximum retail price, tax-inclusive, two decimals
 * @param ceilingUomCode   the unit it is stated in (the SKU's base unit until unit conversion lands)
 * @param effectiveTo      the last day, or null for open-ended
 * @param gazetteReference the gazette's number, e.g. "2492/29"
 */
public record EnterControlPrice(
        UUID skuId,
        BigDecimal ceilingPrice,
        String ceilingUomCode,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String gazetteReference) {}
