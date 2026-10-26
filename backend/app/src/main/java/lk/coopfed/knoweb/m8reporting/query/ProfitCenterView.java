package lk.coopfed.knoweb.m8reporting.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Profit center reporting dimension as viewed by the caller's scope.
 */
public record ProfitCenterView(
        UUID profitCenterId,
        UUID ownerEntityId,
        String code,
        String name,
        String category,
        UUID locationId,
        String locationName,
        String status,
        Instant createdAt) {}
