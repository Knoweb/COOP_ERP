package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Public view representing an active near-expiry or slow-moving discount (PGL-M1-07).
 */
public record ExpirySlowMovingDiscountView(
        UUID skuId,
        String skuCode,
        UUID stockLotId,
        UUID batchId,
        String batchNo,
        UUID locationId,
        String reason, // NEAR_EXPIRY, SLOW_MOVING
        String tierOrClassification, // CRITICAL, WARNING, STAGNANT, SLOW_VELOCITY
        BigDecimal basePrice,
        BigDecimal markdownPercent,
        BigDecimal discountedPrice,
        BigDecimal qtyOnHand,
        LocalDate expiryDate,
        Integer daysToExpiry,
        LocalDate validFrom,
        LocalDate validTo,
        String status // ACTIVE
        ) {}
