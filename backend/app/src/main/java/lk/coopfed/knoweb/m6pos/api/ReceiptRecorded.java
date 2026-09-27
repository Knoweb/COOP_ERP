package lk.coopfed.knoweb.m6pos.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Central recorded a receipt a till issued (26A section 10, ReceiptBundleHook). M5 deducts the
 * stock from the till's own {@code receipt.issued.v1}; this event says M6 has the receipt.
 *
 * @param flags what central found odd about it (never a refusal)
 */
public record ReceiptRecorded(
        UUID documentId,
        UUID ownerEntityId,
        UUID locationId,
        String docNumberDisplay,
        BigDecimal grossAmount,
        int lines,
        List<String> flags)
        implements DomainEvent {

    public static final String TYPE = "receipt.recorded.v1";
}
