package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A warehouse user replaced one FEFO-suggested lot while leaving the original pick immutable. */
public record PickOverridden(
        UUID overrideId,
        UUID ownerEntityId,
        UUID pickListId,
        UUID pickLineId,
        UUID originalStockLotId,
        UUID replacementStockLotId,
        BigDecimal qty,
        String reason)
        implements DomainEvent {

    public static final String TYPE = "pick.overridden.v1";
}
