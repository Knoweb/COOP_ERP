package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * price_list.drafted.v1: a list or a new version was drafted. Not in doc 23 section 5.3, which
 * names no event for a draft; the build requires every handler to publish one (AGENTS.md), and
 * the M3 README records it.
 */
public record PriceListDrafted(UUID priceListId, UUID rootPriceListId, UUID ownerEntityId, String kind, int version)
        implements DomainEvent {

    public static final String TYPE = "price_list.drafted.v1";
}
