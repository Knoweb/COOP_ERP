package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * RecordStockTakeScan (PGL-M2-09): record a barcode or SKU scan in a stock-take session.
 * Idempotently deduplicated by scanId: repeated submissions with identical attributes do not double-count.
 */
public record RecordStockTakeScan(
        UUID sessionId,
        UUID scanId,
        String barcode,
        UUID skuId,
        String skuCode,
        UUID batchId,
        LotCondition condition,
        BigDecimal quantity,
        Instant scannedAt,
        String deviceId,
        String notes) {}
