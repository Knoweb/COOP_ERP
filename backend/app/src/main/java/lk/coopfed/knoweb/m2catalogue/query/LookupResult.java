package lk.coopfed.knoweb.m2catalogue.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The item card data of a scan (22A section 5, LookupResult). A missing Sinhala or Tamil name is
 * answered with the English one and the matching fallback flag set, so the till can mark it.
 * {@code factorToBase} is null when no conversion of the code's unit is in force today (doc 22
 * section 6.6: a missing conversion blocks the document line in that unit).
 */
public record LookupResult(
        UUID skuId,
        String skuCode,
        String nameEn,
        String nameSi,
        String nameTa,
        boolean fallbackSi,
        boolean fallbackTa,
        String uomCode,
        BigDecimal factorToBase,
        BatchRef batch,
        String thumbKey,
        boolean sellThrough,
        boolean hasPrintedMrp,
        boolean soldByWeight) {

    /** The batch the code identifies, or the one the lot resolved to; null when neither. */
    public record BatchRef(UUID batchId, String batchNo, LocalDate expiryDate, BigDecimal printedMrp) {}
}
