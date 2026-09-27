package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * discrepancy.raised.v1 (doc 24 section 5.3): the GRN confirmation found a variance or damage
 * and raised the discrepancy document, at the GRN's location, DISPUTES-linked to the GRN.
 *
 * @param kind SHORT, OVER, DAMAGED or MIXED
 */
public record DiscrepancyRaised(
        UUID discrepancyId,
        String docNumberDisplay,
        UUID grnId,
        UUID deliveryDocumentId,
        UUID receiverEntityId,
        UUID receiverLocationId,
        UUID sellerEntityId,
        String kind,
        Instant windowEndsAt,
        List<DiscrepancyLine> lines)
        implements DomainEvent {

    public static final String TYPE = "discrepancy.raised.v1";
}
