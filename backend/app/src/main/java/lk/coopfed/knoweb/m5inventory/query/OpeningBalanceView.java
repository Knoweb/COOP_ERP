package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An opening balance and its counted lines (doc 25 section 4.7): DRAFT, SIGNED_ENTITY, POSTED or REJECTED. */
public record OpeningBalanceView(
        UUID openingBalanceId,
        UUID locationId,
        String status,
        UUID preparedBy,
        UUID signedEntityBy,
        UUID countersignedBy,
        UUID documentId,
        UUID rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        List<Line> lines) {

    public OpeningBalanceView(
            UUID openingBalanceId,
            UUID locationId,
            String status,
            UUID preparedBy,
            UUID signedEntityBy,
            UUID countersignedBy,
            UUID documentId,
            List<Line> lines) {
        this(
                openingBalanceId,
                locationId,
                status,
                preparedBy,
                signedEntityBy,
                countersignedBy,
                documentId,
                null,
                null,
                null,
                lines);
    }

    public record Line(
            int lineNo,
            UUID batchId,
            UUID skuId,
            String condition,
            BigDecimal qty,
            BigDecimal unitCost,
            String batchNo,
            LocalDate expiryDate,
            BigDecimal printedMrp) {}
}
