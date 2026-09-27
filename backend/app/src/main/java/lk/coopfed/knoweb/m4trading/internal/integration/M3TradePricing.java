package lk.coopfed.knoweb.m4trading.internal.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m4trading.api.TradePricing;
import org.springframework.stereotype.Component;

/**
 * M4's price question answered by M3 (24A section 2, ResolveTradePrice; doc 23 section 5.2): the
 * TRADE list the relationship binds, the newest version published on or before the date, the tier
 * of the ordered quantity (DR-2). Replaced the register's demo answer in M4-04, when M3-04 (#153)
 * published {@link PricingQueries#resolveTradePrice}. Lines are priced in the item's base unit (M3
 * defers other units, as M4 does).
 */
@Component
class M3TradePricing implements TradePricing {

    private final PricingQueries pricing;

    M3TradePricing(PricingQueries pricing) {
        this.pricing = pricing;
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
        Optional<lk.coopfed.knoweb.m3pricing.query.TradePrice> price = relationshipId != null
                ? pricing.resolveTradePrice(relationshipId, skuId, uomCode, qty, onDate, scope)
                : pricing.resolveTradePrice(sellerEntityId, buyerEntityId, skuId, uomCode, qty, onDate, scope);
        return price.map(found -> new TradePrice(found.unitPrice(), found.priceListId()));
    }
}
