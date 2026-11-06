package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read view for a payment transaction / intent (M3-07).
 */
public record PaymentIntentView(
        UUID paymentId,
        UUID ownerEntityId,
        String orderReference,
        String customerRef,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String status,
        String provider,
        String providerRef,
        String qrPayload,
        String description,
        String failureReason,
        String idempotencyKey,
        Map<String, Object> metadata,
        Instant initiatedAt,
        Instant settledAt,
        Instant createdAt,
        Instant updatedAt) {}
