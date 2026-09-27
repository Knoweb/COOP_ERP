package lk.coopfed.knoweb.m4trading.internal.document;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;

/** Builders of the kernel base rows every trading document stands on (K-07). */
public final class TradingDocuments {

    public static final String DRAFT = "DRAFT";
    public static final String ISSUED = "ISSUED";
    public static final String CURRENCY = "LKR";

    private TradingDocuments() {}

    /** A draft header: no number, no hash, no totals; the issuance protocol sets them. */
    public static DocumentRecord draft(
            UUID id,
            String docTypeCode,
            UUID owner,
            UUID counterparty,
            UUID locationId,
            UUID operatorUserId,
            UUID referenceDocumentId,
            String notes) {
        return new DocumentRecord(
                id,
                docTypeCode,
                null,
                null,
                null,
                owner,
                counterparty,
                locationId,
                null,
                null,
                DRAFT,
                null,
                null,
                null,
                operatorUserId,
                CURRENCY,
                null,
                null,
                null,
                referenceDocumentId,
                null,
                DocumentOrigin.ONLINE,
                null,
                notes);
    }

    /** A line with a price and tax (null where the document carries none). */
    public static DocumentLineRecord line(
            UUID id,
            UUID documentId,
            int lineNo,
            UUID skuId,
            UUID batchId,
            String uomCode,
            BigDecimal qty,
            BigDecimal unitPrice,
            BigDecimal taxRatePercent,
            BigDecimal taxAmount,
            BigDecimal lineTotal,
            BigDecimal unitCost,
            UUID referenceLineId) {
        return new DocumentLineRecord(
                id,
                documentId,
                lineNo,
                skuId,
                batchId,
                uomCode,
                qty,
                unitPrice,
                null,
                null,
                null,
                null,
                null,
                taxRatePercent,
                taxAmount,
                lineTotal,
                unitCost,
                null,
                referenceLineId);
    }
}
