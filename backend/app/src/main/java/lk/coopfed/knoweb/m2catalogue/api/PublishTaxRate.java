package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Publish a tax rate for a tax category (22A section 6; doc 22 section 3.6).
 * Effective from {@code applyFrom}; closes the open-ended rate in force at {@code applyFrom - 1}.
 * Only the Federation may publish tax rates.
 */
public record PublishTaxRate(UUID taxCategoryId, BigDecimal ratePercent, LocalDate applyFrom) {}
