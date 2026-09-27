package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * price_list.published.v1 (doc 23 section 5.3: "list id, kind, owner, version, apply_from,
 * changed sku ids"). Consumers: M2's assortment and the snapshot change log for RETAIL lists, M4
 * for trade prices, M8. {@code skuIds} is every SKU of the version: without carry-forward
 * (deferred for the demo) every line of a new version is a changed line.
 *
 * @param rootPriceListId the id of version 1, which relationships bind
 */
public record PriceListPublished(
        UUID priceListId,
        UUID rootPriceListId,
        String kind,
        UUID ownerEntityId,
        int version,
        LocalDate applyFrom,
        List<UUID> skuIds)
        implements DomainEvent {

    public static final String TYPE = "price_list.published.v1";
}
