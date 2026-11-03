package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Parameters for calculating a revenue estimate and product mix plan.
 *
 * @param targetRevenue target revenue amount (positive)
 * @param locationId optional specific location/shop for price resolution
 * @param priceType pricing basis (RETAIL or COMMERCIAL)
 * @param businessDate effective date for price resolution
 * @param items list of products and their target mix percentages
 */
public record RevenueEstimatePlan(
        BigDecimal targetRevenue, UUID locationId, String priceType, LocalDate businessDate, List<MixItem> items) {

    public RevenueEstimatePlan {
        items = items == null ? List.of() : List.copyOf(items);
        priceType = priceType == null || priceType.isBlank()
                ? "RETAIL"
                : priceType.trim().toUpperCase();
    }

    public record MixItem(UUID skuId, BigDecimal mixPercent) {}
}
