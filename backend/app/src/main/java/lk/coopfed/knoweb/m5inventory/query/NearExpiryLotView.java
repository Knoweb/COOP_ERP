package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Near-expiry lot view with remaining quantity and urgency categorization (PGL-M2-07).
 */
public record NearExpiryLotView(
        UUID stockLotId,
        UUID locationId,
        UUID skuId,
        UUID batchId,
        String batchNo,
        LocalDate expiryDate,
        int daysToExpiry,
        BigDecimal qtyOnHand,
        BigDecimal unitCost,
        String urgencyTier,
        boolean alertActive) {}
