package lk.coopfed.knoweb.m3pricing.query;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One pricing review (23A section 5, listReviews). {@code status} is OPEN for every review until a
 * settle command exists; escalation is not in the contract yet (no source for it).
 */
public record ReviewItem(
        UUID reviewId,
        String kind,
        String severity,
        String status,
        OffsetDateTime occurredAt,
        UUID ownerEntityId,
        UUID skuId,
        String skuName,
        String skuCode,
        Map<String, Object> details) {

    public static final String OPEN = "OPEN";
    public static final String SETTLED = "SETTLED";
}
