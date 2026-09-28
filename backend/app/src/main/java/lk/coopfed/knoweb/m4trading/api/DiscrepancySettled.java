package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * discrepancy.settled.v1: the seller accepted the buyer's count. {@code creditNoteId} is the credit
 * note for damaged quantity the invoice charged, null when nothing billed needed crediting (a
 * short quantity is never billed: the invoice is at the received quantity, DR-2).
 */
public record DiscrepancySettled(
        UUID discrepancyId,
        UUID grnId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        UUID creditNoteId,
        UUID settledByUserId,
        Instant settledAt)
        implements DomainEvent {

    public static final String TYPE = "discrepancy.settled.v1";
}
