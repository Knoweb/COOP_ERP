package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A repack consumed an input lot and produced an output batch; the yield variance is expected minus actual. */
public record RepackExecuted(
        UUID repackId,
        UUID ownerEntityId,
        UUID locationId,
        UUID recipeId,
        UUID outputBatchId,
        BigDecimal actualOutputQty,
        BigDecimal varianceQty)
        implements DomainEvent {

    public static final String TYPE = "repack.executed.v1";
}
