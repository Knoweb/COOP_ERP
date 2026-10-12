package lk.coopfed.knoweb.m3pricing.query;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ReviewItem(
        UUID reviewId,
        String kind,
        String severity,
        OffsetDateTime occurredAt,
        UUID ownerEntityId,
        UUID skuId,
        String skuName,
        String skuCode,
        Map<String, Object> details,
        boolean escalated) {}
