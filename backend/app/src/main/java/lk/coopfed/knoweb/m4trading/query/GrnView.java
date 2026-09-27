package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A goods received note as the receiver and the seller see it (doc 24 section 5.2). Status DRAFT
 * or CONFIRMED; {@code discrepancyId} names the discrepancy its confirmation raised.
 */
public record GrnView(
        UUID grnId,
        String docNumberDisplay,
        String status,
        UUID receiverEntityId,
        UUID receiverLocationId,
        UUID sellerEntityId,
        UUID dropId,
        UUID deliveryNoteId,
        LocalDate receivedOn,
        Instant confirmedAt,
        UUID discrepancyId,
        List<GrnLineView> lines) {

    public record GrnLineView(
            UUID lineId,
            int lineNo,
            UUID skuId,
            String uomCode,
            BigDecimal expectedQty,
            BigDecimal receivedQty,
            BigDecimal damagedQty,
            String batchNo,
            LocalDate expiryDate,
            BigDecimal printedMrp,
            BigDecimal unitCost,
            UUID batchId) {}
}
