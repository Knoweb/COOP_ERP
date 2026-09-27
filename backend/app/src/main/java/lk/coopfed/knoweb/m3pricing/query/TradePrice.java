package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The trade price of an order line (doc 23 section 5.2, ResolveTradePrice): tax-exclusive, per
 * unit, four decimals (doc 18: unit price numeric(14,4)). M4 multiplies and adds the tax.
 *
 * @param priceListId   the version of the list the price was read from
 * @param lineId        the line, which the order line may cite
 * @param tierFromQty   the tier that applied (the highest not above the ordered quantity)
 * @param engineVersion the shared engine that chose it
 */
public record TradePrice(
        UUID relationshipId,
        UUID priceListId,
        UUID lineId,
        UUID skuId,
        String uomCode,
        BigDecimal tierFromQty,
        BigDecimal unitPrice,
        String engineVersion) {}
