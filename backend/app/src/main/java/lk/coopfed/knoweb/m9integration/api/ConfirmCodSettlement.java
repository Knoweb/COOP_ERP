package lk.coopfed.knoweb.m9integration.api;

import java.util.Map;
import java.util.UUID;

/**
 * Command to confirm collection and settlement of a Cash on Delivery (COD) payment (M3-07).
 */
public record ConfirmCodSettlement(UUID paymentId, String receiptReference, Map<String, Object> metadata) {

    public ConfirmCodSettlement {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
