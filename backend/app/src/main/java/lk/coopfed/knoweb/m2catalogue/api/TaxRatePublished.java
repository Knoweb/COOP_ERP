package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** tax_rate.published.v1 (doc 22 section 5.3: category, rate, apply_from). */
public record TaxRatePublished(UUID taxCategoryId, BigDecimal ratePercent, LocalDate applyFrom) implements DomainEvent {

    public static final String TYPE = "tax_rate.published.v1";
}
