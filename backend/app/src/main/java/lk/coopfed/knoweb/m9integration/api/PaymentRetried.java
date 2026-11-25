package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a payment transaction is retried with a provider (M3-07).
 */
public record PaymentRetried(
        UUID paymentId, UUID ownerEntityId, String orderReference, String provider, int retryCount, Instant retriedAt)
        implements DomainEvent {

    public static final String TYPE = "integration.payment_retried.v1";
}
