package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Command to open expiry review tasks and schedule an associated count task for a location
 * (doc 25 section 4.6; 25A section 7.1).
 */
public record OpenExpiryTasks(UUID locationId, List<Batch> batches) {

    public record Batch(UUID batchId, UUID skuId, int daysToExpiry, BigDecimal qtyAtOpen) {}
}
