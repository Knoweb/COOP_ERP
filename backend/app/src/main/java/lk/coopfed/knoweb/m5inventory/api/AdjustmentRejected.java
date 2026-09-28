package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** The variances of a count beyond tolerance were rejected; nothing was posted for them. */
public record AdjustmentRejected(UUID taskId, UUID ownerEntityId, UUID locationId, BigDecimal value)
        implements DomainEvent {

    public static final String TYPE = "adjustment.rejected.v1";
}
