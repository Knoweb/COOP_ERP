package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** The variances of a count beyond tolerance were approved and posted as COUNT_ADJUST. */
public record AdjustmentApproved(
        UUID taskId, UUID ownerEntityId, UUID locationId, BigDecimal value, int band, UUID approverUserId)
        implements DomainEvent {

    public static final String TYPE = "adjustment.approved.v1";
}
