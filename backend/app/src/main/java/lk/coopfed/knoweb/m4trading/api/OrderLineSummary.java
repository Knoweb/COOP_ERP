package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order as the order events carry it (doc 24 section 5.3: "lines summary").
 *
 * @param allocatedQty what the seller allocated; null until the order is accepted
 * @param tierPrice    the trade price per unit the seller resolved at acceptance; null until then
 */
public record OrderLineSummary(
        UUID lineId,
        int lineNo,
        UUID skuId,
        String uomCode,
        BigDecimal requestedQty,
        BigDecimal allocatedQty,
        BigDecimal tierPrice) {}
