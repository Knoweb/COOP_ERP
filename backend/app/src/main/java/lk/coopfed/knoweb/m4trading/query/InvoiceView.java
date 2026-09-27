package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A tax invoice as the seller and the buyer see it (doc 24 section 5.2). */
public record InvoiceView(
        UUID invoiceId,
        String docNumberDisplay,
        String status,
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String sellerVatNo,
        String buyerVatNo,
        List<UUID> grnIds,
        LocalDate taxPointDate,
        LocalDate dueDate,
        Instant issuedAt,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        List<InvoiceLineView> lines) {

    public record InvoiceLineView(
            UUID lineId,
            int lineNo,
            UUID skuId,
            UUID batchId,
            String uomCode,
            BigDecimal qty,
            BigDecimal unitPrice,
            BigDecimal taxRatePercent,
            BigDecimal taxAmount,
            BigDecimal lineTotal,
            UUID grnLineId) {}
}
