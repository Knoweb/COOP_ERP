package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * OpenStockTakeSession (PGL-M2-09): opens or resumes a stock-count session for a location.
 * If an active count exists at the location in COUNTING, it returns that session.
 * If an open count exists in SCHEDULED, it transitions it to COUNTING and snapshots expectations.
 * If no open count exists, it schedules and starts a count in one step.
 */
public record OpenStockTakeSession(UUID locationId, String scopeKind, List<UUID> skuIds) {}
