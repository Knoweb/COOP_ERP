package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * grn.confirmed.v1, the pivot (AGENTS.md idea 2; doc 24 section 3.3; 24A section 6.1): ownership
 * of the goods passed to the receiver. Batches are registered (each line carries its batch id),
 * M5 creates the lots and posts RECEIPT movements, invoicing is enabled. Frozen at M4-05 as 24A
 * section 10 asks: M5 and M6 build against this record.
 *
 * @param sellerEntityId  the delivery's seller; null for a local supply
 * @param relationshipId  the relationship the delivery was made under; null for a local supply
 * @param dropId          the delivery note drop received; null for a local supply
 * @param supplierId      the supplier of a local supply; null for a delivery
 * @param variance        true when a discrepancy was raised with it (any line short, over or damaged)
 */
public record GrnConfirmed(
        UUID grnId,
        String docNumberDisplay,
        UUID receiverEntityId,
        UUID receiverLocationId,
        UUID sellerEntityId,
        UUID relationshipId,
        UUID dropId,
        UUID deliveryDocumentId,
        UUID supplierId,
        Instant confirmedAt,
        boolean variance,
        List<GrnLineConfirmed> lines)
        implements DomainEvent {

    public static final String TYPE = "grn.confirmed.v1";
}
