package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * credit_note.issued.v1 (24A section 6): the seller credited its invoice; {@code discrepancyId}
 * names the buyer's discrepancy the credit note settles, null for a credit note of chosen lines.
 */
public record CreditNoteIssued(
        UUID creditNoteId,
        String docNumberDisplay,
        UUID invoiceId,
        UUID discrepancyId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        String contentHash)
        implements DomainEvent {

    public static final String TYPE = "credit_note.issued.v1";
}
