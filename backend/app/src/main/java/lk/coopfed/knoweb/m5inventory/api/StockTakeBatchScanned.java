package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * StockTakeBatchScanned (PGL-M2-09): emitted when a batch of mobile scans is ingested.
 */
public record StockTakeBatchScanned(
        UUID sessionId, UUID ownerEntityId, UUID locationId, int processedCount, int duplicateCount)
        implements DomainEvent {

    public static final String TYPE = "inventory.stock_take.batch_scanned.v1";
}
