package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Society-owned fixed asset view in the caller's permitted scope (Polgolla M1-04).
 */
public record AssetView(
        UUID assetId,
        UUID ownerEntityId,
        String assetCode,
        String name,
        String description,
        String assetType,
        UUID locationId,
        String locationName,
        LocalDate acquisitionDate,
        BigDecimal acquisitionCost,
        String status,
        Instant createdAt) {}
