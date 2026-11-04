package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * StockTakeSessionReconciled (PGL-M2-09): emitted when a stock-take session is reconciled.
 */
public record StockTakeSessionReconciled(
        UUID sessionId,
        UUID ownerEntityId,
        UUID locationId,
        int linesCount,
        int postedCount,
        int reviewCount,
        BigDecimal reviewValue,
        Integer band)
        implements DomainEvent {

    public static final String TYPE = "inventory.stock_take.session_reconciled.v1";
}
