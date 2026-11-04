package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockTakeScanView(
        UUID scanId,
        UUID sessionId,
        String scannedCode,
        UUID skuId,
        String skuCode,
        String skuNameEn,
        UUID batchId,
        String batchNo,
        String condition,
        BigDecimal countedQty,
        Instant scannedAt,
        UUID scannedBy,
        String deviceId,
        String notes) {}
