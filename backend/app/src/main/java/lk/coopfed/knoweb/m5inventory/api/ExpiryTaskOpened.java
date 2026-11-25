package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An expiry review task was opened for a batch entering its warning window (doc 25 section 4.6; 25A section 7.1).
 */
public record ExpiryTaskOpened(
        UUID taskId,
        UUID ownerEntityId,
        UUID locationId,
        UUID batchId,
        UUID skuId,
        int daysToExpiry,
        BigDecimal qtyAtOpen,
        UUID countTaskId)
        implements DomainEvent {

    public static final String TYPE = "expiry_task.opened.v1";
}
