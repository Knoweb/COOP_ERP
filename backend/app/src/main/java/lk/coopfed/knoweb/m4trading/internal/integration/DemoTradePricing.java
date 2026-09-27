package lk.coopfed.knoweb.m4trading.internal.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.TradePricing;
import org.springframework.stereotype.Component;

/**
 * The demo's answer to {@link TradePricing} until M3's ResolveTradePrice exists (M4-02, decided
 * 27 September 2026): one price per unit for every item, the register item
 * {@code m4.demo.trade_price}, which a demo can set per seller entity. No price is hard-coded
 * (AGENTS.md); no tier, no list. M3's implementation replaces this class.
 */
@Component
class DemoTradePricing implements TradePricing {

    static final String PRICE_ITEM = "m4.demo.trade_price";

    private final ConfigRegistry config;

    DemoTradePricing(ConfigRegistry config) {
        this.config = config;
    }

    @Override
    public Optional<TradePrice> resolve(
            UUID relationshipId,
            UUID sellerEntityId,
            UUID buyerEntityId,
            UUID skuId,
            String uomCode,
            BigDecimal qty,
            LocalDate onDate,
            ScopeContext scope) {
        return config.get(PRICE_ITEM, scope).map(BigDecimal::new).map(price -> new TradePrice(price, null));
    }
}
