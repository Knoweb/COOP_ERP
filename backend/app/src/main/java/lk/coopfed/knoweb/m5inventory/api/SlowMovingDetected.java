package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a SKU at a location is classified as slow-moving or stagnant (PGL-M2-07).
 * Consumed by Member 3's Notification Gateway and Member 1's Pricing discounts.
 */
public record SlowMovingDetected(
        UUID alertId,
        UUID ownerEntityId,
        UUID locationId,
        UUID skuId,
        BigDecimal qtyOnHand,
        int daysWithoutSale,
        LocalDate lastSaleDate,
        BigDecimal salesInWindow,
        BigDecimal averageDailySales,
        BigDecimal daysOfSupply,
        String classification,
        Instant detectedAt)
        implements DomainEvent {

    public static final String TYPE = "inventory.slow_moving_detected.v1";
}
