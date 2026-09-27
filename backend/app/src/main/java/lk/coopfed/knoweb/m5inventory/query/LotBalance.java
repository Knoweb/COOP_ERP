package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One lot as the reads show it (25A section 5, balances).
 *
 * @param fefoRank  1 for the GOOD lot to sell or pick first at its location for its SKU (expiry,
 *                  none last, then received); null for a DAMAGED, empty or negative lot
 * @param unitCost  the lot's acquisition cost; the web layer leaves it out for a till and for a
 *                  read-only class
 * @param negative  the lot is below zero (an oversell), flagged since {@code negativeSince}
 */
public record LotBalance(
        UUID stockLotId,
        UUID ownerEntityId,
        UUID locationId,
        UUID skuId,
        UUID batchId,
        LocalDate expiryDate,
        String condition,
        BigDecimal qtyOnHand,
        Integer fefoRank,
        BigDecimal unitCost,
        Instant receivedAt,
        boolean negative) {}
