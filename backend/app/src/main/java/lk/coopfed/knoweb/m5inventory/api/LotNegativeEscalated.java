package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An oversold stock lot remained unacknowledged for the escalation threshold (7 days)
 * and has escalated (doc 25 section 3.2; 25A section 7.2; ticket M5-06b / INV-04).
 */
public record LotNegativeEscalated(
        UUID stockLotId,
        UUID ownerEntityId,
        UUID locationId,
        UUID batchId,
        UUID skuId,
        BigDecimal qtyOnHand,
        Instant negativeSince,
        int daysNegative)
        implements DomainEvent {

    public static final String TYPE = "lot.negative.escalated.v1";
}
