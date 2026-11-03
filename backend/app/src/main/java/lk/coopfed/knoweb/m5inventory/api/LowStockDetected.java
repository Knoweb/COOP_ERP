package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Stock at a location fell to or below its configured reorder level (PGL-M2-01).
 *
 * <p>Published to the outbox for consumption by downstream services such as Member 3's Notification Gateway
 * to dispatch automated SMS and messaging alerts to storekeepers and managers.
 */
public record LowStockDetected(
        UUID reorderControlId,
        UUID ownerEntityId,
        UUID locationId,
        UUID skuId,
        BigDecimal onHandQty,
        BigDecimal availableQty,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal maxQty,
        Instant detectedAt)
        implements DomainEvent {

    public static final String TYPE = "inventory.low_stock_detected.v1";
}
