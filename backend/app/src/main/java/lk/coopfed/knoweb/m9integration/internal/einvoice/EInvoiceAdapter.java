package lk.coopfed.knoweb.m9integration.internal.einvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Provider-neutral e-invoicing submission boundary (ACC-10, M9-06).
 *
 * Phase 1 uses a stub. Live IRD integration belongs to Phase 3.
 * The adapter must not change the issued invoice.
 */
public interface EInvoiceAdapter {

    SubmissionResult submit(InvoiceSnapshot invoice);

    /**
     * Immutable information supplied by the invoice. M9 receives
     * these fields from invoice.issued.v1 without reading M4 tables.
     */
    record InvoiceSnapshot(
            UUID invoiceId,
            UUID sellerEntityId,
            UUID buyerEntityId,
            String docNumberDisplay,
            String sellerVatNo,
            String buyerVatNo,
            LocalDate taxPointDate,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal grossAmount,
            String contentHash) {}

    /**
     * Provider response. The Phase 1 stub always returns PENDING.
     */
    record SubmissionResult(String status, String providerReference, String responseKey) {}
}
