package lk.coopfed.knoweb.m2catalogue.internal.assortment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal entity representation for catalogue.location_assortment.
 */
public record AssortmentRecord(
        UUID locationId, UUID skuId, List<String> reasons, boolean sellThrough, Instant since, UUID ownerEntityId) {}
