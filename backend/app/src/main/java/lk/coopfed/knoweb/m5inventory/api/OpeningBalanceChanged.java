package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An opening balance was prepared or signed (doc 25 section 5.3, "opening_balance.*.v1").
 *
 * @param status DRAFT when prepared, SIGNED_ENTITY when the entity signed
 */
public record OpeningBalanceChanged(UUID openingBalanceId, UUID ownerEntityId, UUID locationId, String status)
        implements DomainEvent {

    public static final String TYPE = "opening_balance.changed.v1";
}
