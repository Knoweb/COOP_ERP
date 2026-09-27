package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The price question M4 asks of M3 (24A section 2: "ResolveTradePrice(relationship, sku, uom,
 * qty, date)"; doc 24 section 3.6: tier by the ordered quantity, DR-2). M4 keeps the question in its
 * own api so that its handlers do not change with the answer; since M4-04 the one implementation,
 * {@code internal.integration.M3TradePricing}, asks M3's {@code PricingQueries.resolveTradePrice}
 * (M3-04, #153), and the register's demo answer is gone.
 */
public interface TradePricing {

    /**
     * @param qty the tier basis: the ordered quantity (trading.tier_basis ORDERED_QTY)
     * @return empty when no price list of the relationship prices the item
     */
    Optional<TradePrice> resolve(
            UUID relationshipId,
            UUID sellerEntityId,
            UUID buyerEntityId,
            UUID skuId,
            String uomCode,
            BigDecimal qty,
            LocalDate onDate,
            ScopeContext scope);

    /** A resolved trade price: per unit of the asked unit of measure, before tax. */
    record TradePrice(BigDecimal unitPrice, UUID priceListId) {}
}
