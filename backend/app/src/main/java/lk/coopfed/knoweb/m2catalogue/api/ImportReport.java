package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Report returned by {@link BulkImportSkus} execution (CAT-01).
 *
 * <p>Contains counts of total, accepted, and rejected rows, alongside detailed
 * validation errors and duplicate suspect matches.
 */
public record ImportReport(
        String status,
        int rows,
        int accepted,
        int rejected,
        List<ValidationError> errors,
        List<DuplicateSuspectItem> suspects) {

    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    public record ValidationError(int rowNumber, String field, String code, String message, String params) {}

    public record DuplicateSuspectItem(
            int rowNumber,
            String candidateName,
            UUID matchedSkuId,
            String matchedSkuCode,
            String matchedSkuName,
            BigDecimal score,
            List<String> reasons) {}
}
