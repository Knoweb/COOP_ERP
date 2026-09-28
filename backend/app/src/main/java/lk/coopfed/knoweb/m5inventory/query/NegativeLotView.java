package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A lot below zero, flagged for review (doc 25 flow 6.2): the tills sold more than the book held.
 * The next count corrects it; acknowledging it says somebody has looked.
 */
public record NegativeLotView(
        UUID stockLotId,
        UUID locationId,
        UUID skuId,
        UUID batchId,
        String condition,
        BigDecimal qtyOnHand,
        Instant negativeSince,
        Instant acknowledgedAt) {}
