package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One line of a price list: a SKU in a unit, from a tier quantity, at a price (doc 23 section 3.1). */
public record PriceListLineView(
        UUID lineId,
        UUID priceListId,
        UUID skuId,
        String uomCode,
        BigDecimal tierFromQty,
        BigDecimal price,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
