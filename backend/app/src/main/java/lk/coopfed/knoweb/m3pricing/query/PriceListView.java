package lk.coopfed.knoweb.m3pricing.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One version of a price list (doc 23 section 3.1).
 *
 * @param rootPriceListId the id of version 1: the id a relationship binds
 */
public record PriceListView(
        UUID priceListId,
        UUID rootPriceListId,
        UUID ownerEntityId,
        String kind,
        String name,
        int version,
        UUID sourceVersionId,
        String status,
        LocalDate applyFrom,
        Instant publishedAt,
        Instant createdAt) {}
