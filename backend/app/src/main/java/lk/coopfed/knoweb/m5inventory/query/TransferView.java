package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transfer between two locations of one entity (doc 25 flow 6.6): IN_TRANSIT until the
 * destination receives it, then RECEIVED.
 */
public record TransferView(
        UUID transferId,
        UUID fromLocationId,
        UUID toLocationId,
        String status,
        UUID issuedBy,
        Instant issuedAt,
        UUID receivedBy,
        Instant receivedAt,
        List<Line> lines) {

    public record Line(int lineNo, UUID batchId, UUID skuId, BigDecimal qty, BigDecimal unitCost) {}
}
