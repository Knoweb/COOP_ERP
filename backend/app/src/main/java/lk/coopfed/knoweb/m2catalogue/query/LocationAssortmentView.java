package lk.coopfed.knoweb.m2catalogue.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The assortment row for a location (22A section 7.2; doc 22 section 3.8).
 *
 * @param locationId the location
 * @param skuId the SKU
 * @param reasons reasons the SKU is in assortment (RETAIL_LIST, LOT, LOCAL, CORE_RANGE)
 * @param sellThrough whether an inactive SKU is selling through until out of stock
 * @param since when the SKU joined the assortment
 * @param ownerEntityId the owner entity of the location
 */
public record LocationAssortmentView(
        UUID locationId, UUID skuId, List<String> reasons, boolean sellThrough, Instant since, UUID ownerEntityId) {}
