package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the ledger did with one {@link Movement}, in the order the movements were given.
 *
 * @param movementId         the ledger row
 * @param stockLotId         the lot it moved (created by it when new)
 * @param source             'central' or the device id that numbered it
 * @param movementSeq        dense per (location, source)
 * @param unitCostAtMovement the cost it carries: the caller's for an intake, the entity average otherwise
 * @param lotQtyOnHand       the lot's quantity after it
 */
public record PostedMovement(
        UUID movementId,
        UUID stockLotId,
        String source,
        long movementSeq,
        BigDecimal unitCostAtMovement,
        BigDecimal lotQtyOnHand) {}
