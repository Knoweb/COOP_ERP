package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One change to one lot, as a caller of the ledger asks for it (25A section 6.1). The lot is
 * named by its identity (location, batch, condition) and created by its first movement.
 *
 * @param locationId     where the stock is
 * @param batchId        the batch (M2); the SKU is the batch's
 * @param condition      GOOD or DAMAGED
 * @param type           the kind of movement; it decides the sign allowed and where the cost comes from
 * @param qtyDelta       signed, in the SKU's base unit, scale 3; never zero
 * @param unitCost       required when the type carries its own cost (an intake, a transfer in),
 *                       scale 4; ignored otherwise, where the entity average applies
 * @param documentLineId the line of the cited document, or null
 */
public record Movement(
        UUID locationId,
        UUID batchId,
        LotCondition condition,
        MovementType type,
        BigDecimal qtyDelta,
        BigDecimal unitCost,
        UUID documentLineId) {}
