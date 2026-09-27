package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * invoice.issued.v1 (doc 24 section 5.3): the seller's tax invoice from confirmed GRNs, with the
 * VAT numbers, the totals and the snapshot hash M9's e-invoice seam relies on (J-04).
 */
public record InvoiceIssued(
        UUID invoiceId,
        String docNumberDisplay,
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String sellerVatNo,
        String buyerVatNo,
        List<UUID> grnIds,
        LocalDate taxPointDate,
        LocalDate dueDate,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        String contentHash)
        implements DomainEvent {

    public static final String TYPE = "invoice.issued.v1";
}
