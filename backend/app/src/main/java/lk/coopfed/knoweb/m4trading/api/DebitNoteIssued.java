package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * debit_note.issued.v1: the seller debited its invoice.
 */
public record DebitNoteIssued(
        UUID debitNoteId,
        String docNumberDisplay,
        UUID invoiceId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        String contentHash)
        implements DomainEvent {

    public static final String TYPE = "debit_note.issued.v1";
}
