package lk.coopfed.knoweb.m2catalogue.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A tax rate row (22A section 3.1) of a tax category, with its effective range.
 */
public record TaxRateHistoryView(
        UUID taxCategoryId,
        String taxCategoryCode,
        BigDecimal ratePercent,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
