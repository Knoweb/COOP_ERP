package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a payment transaction is initiated or LankaQR generated (M3-07).
 */
public record PaymentInitiated(
        UUID paymentId,
        UUID ownerEntityId,
        String orderReference,
        String customerRef,
        String paymentMethod,
        BigDecimal amount,
        String status,
        String provider,
        String providerRef,
        String qrPayload,
        Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "integration.payment_initiated.v1";
}
