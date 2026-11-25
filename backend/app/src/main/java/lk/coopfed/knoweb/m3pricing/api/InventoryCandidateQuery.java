package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Question interface published by M3 Pricing to query near-expiry and slow-moving inventory candidates
 * without depending directly on M5's query package (PGL-M1-07; layer rule ADR-05).
 * M5 implements this question.
 */
public interface InventoryCandidateQuery {

    /**
     * Near-expiry lots with stock at a location.
     */
    List<NearExpiryCandidate> nearExpiryCandidates(UUID locationId, ScopeContext scope);

    /**
     * Slow-moving inventory items at a location.
     */
    List<SlowMovingCandidate> slowMovingCandidates(UUID locationId, ScopeContext scope);

    record NearExpiryCandidate(
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

    record SlowMovingCandidate(
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
}
