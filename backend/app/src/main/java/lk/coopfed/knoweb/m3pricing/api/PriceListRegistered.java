package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a price list has been registered, in the same transaction as the insert.
 *
 * <p>An event payload holds identifiers and changed facts only (17A section 4.4; doc 19
 * section 6.1): a consumer that needs the texts reads them through {@link PriceListQueries}.
 * Never put a name, phone number, NIC, PIN or token in an event.
 *
 * @param priceListId    the new price list
 * @param ownerEntityId the entity that owns it
 */
public record PriceListRegistered(UUID priceListId, UUID ownerEntityId) implements DomainEvent {

    /** Dotted, versioned, never reused: a changed payload is a new version (.v2). */
    public static final String TYPE = "price_list.registered.v1";
}
