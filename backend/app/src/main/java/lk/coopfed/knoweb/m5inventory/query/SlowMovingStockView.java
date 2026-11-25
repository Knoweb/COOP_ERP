package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Slow-moving stock view with sales velocity and supply metrics (PGL-M2-07).
 */
public record SlowMovingStockView(
        UUID locationId,
        UUID skuId,
        BigDecimal qtyOnHand,
        BigDecimal unitCost,
        int daysWithoutSale,
        LocalDate lastSaleDate,
        BigDecimal salesInWindow,
        BigDecimal averageDailySales,
        BigDecimal daysOfSupply,
        String classification,
        boolean alertActive) {}
