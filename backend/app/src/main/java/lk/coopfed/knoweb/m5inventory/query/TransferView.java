package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transfer between two locations of one entity (doc 25 flow 6.6): ISSUED until the source
 * dispatches it, IN_TRANSIT until the destination receives it, then RECEIVED.
 */
public record TransferView(
        UUID transferId,
        UUID fromLocationId,
        UUID toLocationId,
        String status,
        String documentNo,
        UUID issuedBy,
        Instant issuedAt,
        UUID dispatchedBy,
        Instant dispatchedAt,
        UUID receivedBy,
        Instant receivedAt,
        UUID shortfallId,
        String shortfallStatus,
        String shortfallCategory,
        List<Line> lines) {

    public record Line(
            UUID transferLineId,
            int lineNo,
            UUID batchId,
            UUID skuId,
            BigDecimal qty,
            BigDecimal unitCost,
            BigDecimal receivedQty,
            BigDecimal shortfallQty) {}
}
