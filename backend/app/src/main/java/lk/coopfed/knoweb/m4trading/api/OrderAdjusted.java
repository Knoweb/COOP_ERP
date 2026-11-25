package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * order.adjusted.v1 (PRC-10; doc 24 §5.3).
 * A seller recorded a manual adjustment (SPECIAL_DISCOUNT or DELIVERY_FEE) on an order.
 */
public record OrderAdjusted(
        UUID adjustmentId,
        UUID orderId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String adjustmentType,
        String kind,
        BigDecimal amount,
        String description,
        Instant recordedAt)
        implements DomainEvent {

    public static final String TYPE = "order.adjusted.v1";
}
