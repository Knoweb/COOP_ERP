package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * BatchStockTakeScan (PGL-M2-09): uploads a batch of queued mobile scans.
 * Idempotent: previously recorded scans in the batch are safely skipped without double-counting.
 */
public record BatchStockTakeScan(UUID sessionId, List<RecordStockTakeScan> scans) {}
