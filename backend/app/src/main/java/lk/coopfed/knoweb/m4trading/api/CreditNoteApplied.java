package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * credit_note.applied.v1 (24A section 6, ApplyCreditNote; CR-24A-3 item 2): money a credit note
 * held unapplied now reduces what is due on an invoice of the same buyer. No money moved and no
 * document was issued: the credit note gained a CREDITS link to the invoice. No journal is posted:
 * the credit note's own posting already credited the buyer's account with its whole amount.
 *
 * @param invoiceId       the invoice it was applied to (not necessarily the one it credits)
 * @param unappliedAmount what the credit note still holds unapplied after it
 */
public record CreditNoteApplied(
        UUID creditNoteId,
        String docNumberDisplay,
        UUID invoiceId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal appliedAmount,
        BigDecimal unappliedAmount)
        implements DomainEvent {

    public static final String TYPE = "credit_note.applied.v1";
}
