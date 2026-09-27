package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** An opening balance and its counted lines (doc 25 section 4.7): DRAFT, SIGNED_ENTITY or POSTED. */
public record OpeningBalanceView(
        UUID openingBalanceId,
        UUID locationId,
        String status,
        UUID preparedBy,
        UUID signedEntityBy,
        UUID countersignedBy,
        UUID documentId,
        List<Line> lines) {

    public record Line(int lineNo, UUID batchId, UUID skuId, String condition, BigDecimal qty, BigDecimal unitCost) {}
}
