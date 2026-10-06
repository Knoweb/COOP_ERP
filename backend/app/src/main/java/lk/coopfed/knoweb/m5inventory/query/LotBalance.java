package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One lot as the reads show it (25A section 5, balances).
 *
 * @param qtyOnHand what the lot holds; from {@code InventoryQueries.pickBatches}, what is free of
 *                  it (less the open pick lists' reservations)
 * @param fefoRank  1 for the GOOD lot to sell or pick first at its location for its SKU (expiry,
 *                  none last, then received); null for a DAMAGED, empty, negative or expired lot
 * @param unitCost  the lot's acquisition cost; the web layer leaves it out for a till and for a
 *                  read-only class
 * @param negative  the lot is below zero (an oversell), flagged since {@code negativeSince}
 * @param expired   its expiry date is before the business date (wave 2, M5-01): never picked,
 *                  never available, never a price candidate; it leaves by a write-off
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
        boolean negative,
        boolean expired) {}
