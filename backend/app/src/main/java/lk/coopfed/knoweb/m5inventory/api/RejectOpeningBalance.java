package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * Command for a higher officer to reject a draft or signed opening balance with a documented reason (PGL-M2-06).
 *
 * <p>Preserves the immutable historical records while freeing the location for re-preparation.
 *
 * @param openingBalanceId ID of the opening balance to reject
 * @param reason documented justification for rejection (e.g. physical recount discrepancy)
 */
public record RejectOpeningBalance(UUID openingBalanceId, String reason) {}
