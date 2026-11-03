package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A draft price-list version was withdrawn. */
public record PriceListWithdrawn(UUID priceListId, UUID rootPriceListId, UUID ownerEntityId, String kind, int version)
        implements DomainEvent {

    public static final String TYPE = "price_list.withdrawn.v1";
}
