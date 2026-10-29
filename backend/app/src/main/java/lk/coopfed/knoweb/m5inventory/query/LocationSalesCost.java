package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregated cost of goods sold (COGS) for sale movements at a location over a period.
 *
 * @param locationId     the location
 * @param recognizedCost the sum of cost of goods sold (abs(qtyDelta) * unitCostAtMovement)
 * @param costPending    true if any sale movement in the period has a non-positive or unresolved unit cost
 * @param movementCount  number of sale movements recorded
 */
public record LocationSalesCost(UUID locationId, BigDecimal recognizedCost, boolean costPending, long movementCount) {}
