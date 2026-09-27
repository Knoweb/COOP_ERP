package lk.coopfed.knoweb.m2catalogue.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The VAT rate in force for a SKU on a date (22A sections 3 and 7): the SKU's tax category and the
 * {@code catalogue.tax_rate} row whose effective range covers the date. EXEMPT and ZERO rated
 * categories carry a 0 % row (doc 22 section 3.6), so their rate is zero.
 */
public record TaxRateView(
        UUID skuId, UUID taxCategoryId, String taxCategoryCode, BigDecimal ratePercent, LocalDate effectiveFrom) {}
