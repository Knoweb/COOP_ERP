package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** Somebody acknowledged a lot below zero (doc 25 section 5.3). */
public record LotAcknowledged(UUID stockLotId, UUID ownerEntityId, UUID locationId, BigDecimal qtyOnHand)
        implements DomainEvent {

    public static final String TYPE = "lot.acknowledged.v1";
}
