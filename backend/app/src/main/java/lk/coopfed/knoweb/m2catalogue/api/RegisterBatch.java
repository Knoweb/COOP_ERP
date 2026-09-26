package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RegisterBatch, the internal command of 22A section 6 that M4's GRN confirmation and M5's
 * repack call in their own transaction (doc 22 section 3.7, "Who creates batches"), through
 * {@link BatchRegistration}. The same (SKU, supplier, batch number) answers the batch already
 * registered. When the SKU is not batch-tracked, or the supplier printed no number, a synthetic
 * batch is registered, numbered {@code S-<originDocumentNo>-<originLine>}.
 *
 * @param skuId            the item
 * @param supplierId       the supplier, or null (a repack output, a local supply)
 * @param batchNo          the number printed by the supplier; ignored when the SKU is not batch-tracked
 * @param manufactureDate  optional
 * @param expiryDate       required when the SKU is expiry-tracked
 * @param printedMrp       required when the SKU has a printed MRP
 * @param originDocumentId the GRN or repack document
 * @param originDocumentNo its number, for a synthetic batch number
 * @param originLine       the line of the document, for a synthetic batch number
 */
public record RegisterBatch(
        UUID skuId,
        UUID supplierId,
        String batchNo,
        LocalDate manufactureDate,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        UUID originDocumentId,
        String originDocumentNo,
        Integer originLine) {}
