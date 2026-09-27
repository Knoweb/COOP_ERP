package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CaptureGrn (24A section 6), at the web: the receiver counts a delivery note drop at one of its
 * own locations. Each line names the item and what arrived; the drop's lines are the expected
 * quantities, and an expected item not counted is received as zero (short).
 */
public record CaptureGrn(UUID dropId, UUID locationId, LocalDate receivedOn, List<Line> lines) {

    public record Line(
            UUID skuId,
            String uomCode,
            BigDecimal receivedQty,
            BigDecimal damagedQty,
            String batchNo,
            LocalDate manufactureDate,
            LocalDate expiryDate,
            BigDecimal printedMrp) {}
}
