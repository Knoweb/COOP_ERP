package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * StockTakeScanRecorded (PGL-M2-09): emitted when a scan is accepted and stored in the session ledger.
 */
public record StockTakeScanRecorded(
        UUID sessionId,
        UUID scanId,
        UUID ownerEntityId,
        UUID locationId,
        UUID skuId,
        UUID batchId,
        LotCondition condition,
        BigDecimal quantity,
        BigDecimal totalCountedForLot)
        implements DomainEvent {

    public static final String TYPE = "inventory.stock_take.scan_recorded.v1";
}
