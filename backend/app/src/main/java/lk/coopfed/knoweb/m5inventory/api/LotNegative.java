package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A lot went below zero (doc 25 section 3.2: an offline oversell; "lot, qty, since, document that
 * caused it"). It goes to the exception queue; availability no longer counts the lot, and the next
 * count or receipt clears it.
 */
public record LotNegative(
        UUID stockLotId,
        UUID ownerEntityId,
        UUID locationId,
        UUID batchId,
        UUID skuId,
        BigDecimal qtyOnHand,
        Instant since,
        UUID documentId)
        implements DomainEvent {

    public static final String TYPE = "lot.negative.v1";
}
