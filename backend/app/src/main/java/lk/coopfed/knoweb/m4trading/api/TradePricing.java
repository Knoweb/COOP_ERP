package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The price question M4 asks of M3 (24A section 2: "ResolveTradePrice(relationship, sku, uom,
 * qty, date)"; doc 24 section 3.6: tier by the ordered quantity, DR-2). M3's query package does
 * not exist on main yet (the M3 lane is building it), so M4 publishes the question here and the
 * demo answers it from the configuration register ({@code internal.integration.DemoTradePricing},
 * item {@code m4.demo.trade_price}). When M3's {@code ResolveTradePrice} lands, M4 calls it from
 * this seam's one implementation and the demo answer is deleted in the same pull request.
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
