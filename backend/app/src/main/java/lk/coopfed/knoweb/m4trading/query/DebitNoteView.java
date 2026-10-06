package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A debit note as the seller who issued it and the buyer see it: the invoice
 * it debits. Its lines are shaped like an invoice's; {@code grnLineId} there names the invoice line it debits.
 */
public record DebitNoteView(
        UUID debitNoteId,
        String docNumberDisplay,
        String status,
        UUID invoiceId,
        String invoiceDocNumberDisplay,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String reason,
        Instant issuedAt,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        List<InvoiceView.InvoiceLineView> lines) {}
