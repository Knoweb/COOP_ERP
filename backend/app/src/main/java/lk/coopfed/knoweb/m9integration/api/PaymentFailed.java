package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a payment transaction fails or is rejected (M3-07).
 */
public record PaymentFailed(
        UUID paymentId,
        UUID ownerEntityId,
        String orderReference,
        String paymentMethod,
        BigDecimal amount,
        String provider,
        String failureReason,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "integration.payment_failed.v1";
}
