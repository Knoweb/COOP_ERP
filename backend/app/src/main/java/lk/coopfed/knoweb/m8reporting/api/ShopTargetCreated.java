package lk.coopfed.knoweb.m8reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** Published when a shop sales target is created. */
public record ShopTargetCreated(
        UUID targetId,
        UUID ownerEntityId,
        UUID locationId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal targetAmount,
        String currency)
        implements DomainEvent {

    public static final String TYPE = "shop_target.created.v1";
}
