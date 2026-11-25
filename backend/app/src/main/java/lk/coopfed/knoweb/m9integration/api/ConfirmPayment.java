package lk.coopfed.knoweb.m9integration.api;

import java.util.Map;
import java.util.UUID;

/**
 * Command to confirm settlement or report failure from a provider callback / webhook (M3-07).
 */
public record ConfirmPayment(
        UUID paymentId, String providerRef, String status, String failureReason, Map<String, Object> metadata) {

    public ConfirmPayment {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
