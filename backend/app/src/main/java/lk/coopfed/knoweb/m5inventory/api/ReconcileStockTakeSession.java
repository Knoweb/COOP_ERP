package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * ReconcileStockTakeSession (PGL-M2-09): aggregates counted scans into the standard inventory count submit
 * workflow, with configurable handling for uncounted items (ZERO or SKIP).
 */
public record ReconcileStockTakeSession(UUID sessionId, String uncountedHandling, String skipReason) {}
