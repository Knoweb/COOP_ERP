package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Command to initiate an online payment, generate a LankaQR code, or record COD intent (M3-07).
 */
public record InitiatePayment(
        UUID paymentId,
        String orderReference,
        String customerRef,
        BigDecimal amount,
        String paymentMethod,
        String provider,
        String description,
        String idempotencyKey,
        Map<String, Object> metadata) {

    public InitiatePayment {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
