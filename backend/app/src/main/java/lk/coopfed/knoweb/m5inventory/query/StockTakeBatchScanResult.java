package lk.coopfed.knoweb.m5inventory.query;

import java.util.UUID;

public record StockTakeBatchScanResult(UUID sessionId, int processedCount, int duplicateCount, int totalScansCount) {}
