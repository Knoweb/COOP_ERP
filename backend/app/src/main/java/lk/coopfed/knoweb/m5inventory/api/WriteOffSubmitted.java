package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A write-off was issued as a WOF document and waits for its witness. */
public record WriteOffSubmitted(
        UUID writeOffId, UUID ownerEntityId, UUID locationId, String documentNo, BigDecimal value, int band)
        implements DomainEvent {

    public static final String TYPE = "writeoff.submitted.v1";
}
