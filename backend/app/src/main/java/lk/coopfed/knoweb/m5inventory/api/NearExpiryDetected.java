package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a stock lot is detected within its near-expiry window (PGL-M2-07).
 * Consumed by Member 3's Notification Gateway and Member 1's Pricing discounts.
 */
public record NearExpiryDetected(
        UUID alertId,
        UUID ownerEntityId,
        UUID locationId,
        UUID batchId,
        String batchNo,
        UUID skuId,
        LocalDate expiryDate,
        int daysToExpiry,
        BigDecimal qtyOnHand,
        String urgencyTier,
        Instant detectedAt)
        implements DomainEvent {

    public static final String TYPE = "inventory.near_expiry_detected.v1";
}
