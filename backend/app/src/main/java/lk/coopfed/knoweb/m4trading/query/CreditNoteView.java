package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A credit note as the seller who issued it and the buyer see it (doc 24 section 3.6): the invoice
 * it credits and, when it settles one, the buyer's discrepancy. Its lines are shaped like an
 * invoice's; {@code grnLineId} there names the invoice line it credits.
 *
 * @param appliedAmount   what of its gross was applied to invoices (the sum of its CREDITS links:
 *     to the invoice it credits as far as that was due, and later by ApplyCreditNote)
 * @param unappliedAmount what it still holds for the buyer, {@code grossAmount - appliedAmount}
 *     (CR-24A-3 item 2; never stored)
 */
public record CreditNoteView(
        UUID creditNoteId,
        String docNumberDisplay,
        String status,
        UUID invoiceId,
        String invoiceDocNumberDisplay,
        UUID discrepancyId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String reason,
        Instant issuedAt,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        List<InvoiceView.InvoiceLineView> lines,
        BigDecimal appliedAmount,
        BigDecimal unappliedAmount) {}
