package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when an order payment is placed on Cash on Delivery awaiting fulfillment (M3-07).
 */
public record PaymentCodPending(
        UUID paymentId, UUID ownerEntityId, String orderReference, BigDecimal amount, Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "integration.payment_cod_pending.v1";
}
