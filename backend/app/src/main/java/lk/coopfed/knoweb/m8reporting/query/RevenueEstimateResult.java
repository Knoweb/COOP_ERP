package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Result of a calculated revenue estimate and sales mix plan.
 */
public record RevenueEstimateResult(
        BigDecimal targetRevenue,
        BigDecimal totalEstimatedRevenue,
        BigDecimal targetVariance,
        BigDecimal totalMixPercent,
        String currency,
        UUID locationId,
        String priceType,
        LocalDate businessDate,
        List<EstimatedItem> items,
        Assumptions assumptions) {

    public RevenueEstimateResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record EstimatedItem(
            UUID skuId,
            String skuCode,
            String skuName,
            String uomCode,
            BigDecimal unitPrice,
            String priceSource,
            BigDecimal mixPercent,
            BigDecimal targetShare,
            BigDecimal rawQuantity,
            BigDecimal roundedQuantity,
            BigDecimal estimatedRevenue,
            BigDecimal roundingVariance) {}

    public record Assumptions(
            String targetRevenue,
            UUID locationId,
            String priceType,
            String businessDate,
            String roundingMethod,
            String priceResolutionPolicy) {}
}
