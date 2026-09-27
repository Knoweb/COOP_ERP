package lk.coopfed.knoweb.m4trading.internal.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.TaxRates;
import org.springframework.stereotype.Component;

/**
 * The demo's answer to {@link TaxRates} until M2 publishes its rate query (M4-08, decided 27
 * September 2026): one VAT rate for every category, the register item
 * {@code m4.demo.vat_rate_percent}. M2's implementation replaces this class.
 */
@Component
class DemoTaxRates implements TaxRates {

    static final String RATE_ITEM = "m4.demo.vat_rate_percent";

    private final ConfigRegistry config;

    DemoTaxRates(ConfigRegistry config) {
        this.config = config;
    }

    @Override
    public Optional<BigDecimal> ratePercent(UUID taxCategoryId, LocalDate onDate, ScopeContext scope) {
        return config.get(RATE_ITEM, scope).map(BigDecimal::new);
    }
}
