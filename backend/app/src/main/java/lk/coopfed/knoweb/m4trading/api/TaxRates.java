package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The tax question of the invoice builder (24A section 2: "TaxQueries.rateInForce via M2"; doc 24
 * section 3.6: "VAT per line from the SKU's category"). M2 publishes no tax query yet, so M4 asks
 * here and the demo answers from the register ({@code internal.integration.DemoTaxRates}, item
 * {@code m4.demo.vat_rate_percent}). M2's rate query replaces the demo answer.
 */
public interface TaxRates {

    /** The rate in force for the category on the tax point date, as a percentage; empty when none. */
    Optional<BigDecimal> ratePercent(UUID taxCategoryId, LocalDate onDate, ScopeContext scope);
}
