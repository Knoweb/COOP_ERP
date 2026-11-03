package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A single row of an inter-entity receivable opening balance spreadsheet or payload (CAT-12). */
public record ReceivableOpeningRow(
        UUID sellerEntityId,
        String sellerCode,
        UUID buyerEntityId,
        String buyerCode,
        BigDecimal amount,
        LocalDate dueDate,
        LocalDate asOfDate,
        String referenceNo,
        UUID sellerSignedBy,
        String sellerSigner,
        UUID buyerSignedBy,
        String buyerSigner,
        String notes) {

    public ReceivableOpeningRow(
            String buyerCode,
            BigDecimal amount,
            LocalDate dueDate,
            String referenceNo,
            UUID sellerSignedBy,
            UUID buyerSignedBy) {
        this(
                null,
                null,
                null,
                buyerCode,
                amount,
                dueDate,
                null,
                referenceNo,
                sellerSignedBy,
                null,
                buyerSignedBy,
                null,
                null);
    }
}
