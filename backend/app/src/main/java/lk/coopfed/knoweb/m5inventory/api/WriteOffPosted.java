package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A write-off was approved and its WRITE_OFF movements posted; the loss is the owner's (ADR-03). */
public record WriteOffPosted(
        UUID writeOffId, UUID ownerEntityId, UUID locationId, String category, BigDecimal value, UUID approverUserId)
        implements DomainEvent {

    public static final String TYPE = "writeoff.posted.v1";
}
