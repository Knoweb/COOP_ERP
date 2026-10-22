package lk.coopfed.knoweb.m8reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** Published when a shop sales target is updated. */
public record ShopTargetUpdated(
        UUID targetId,
        UUID ownerEntityId,
        UUID locationId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal targetAmount)
        implements DomainEvent {

    public static final String TYPE = "shop_target.updated.v1";
}
