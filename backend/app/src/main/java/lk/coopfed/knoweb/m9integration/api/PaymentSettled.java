package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a payment transaction is successfully settled (M3-07).
 */
public record PaymentSettled(
        UUID paymentId,
        UUID ownerEntityId,
        String orderReference,
        String paymentMethod,
        BigDecimal amount,
        String provider,
        String providerRef,
        Instant settledAt)
        implements DomainEvent {

    public static final String TYPE = "integration.payment_settled.v1";
}
