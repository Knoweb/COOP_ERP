package lk.coopfed.knoweb.m8reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Command to register a society-owned fixed/non-stock asset (Polgolla M1-04).
 *
 * @param assetCode       unique code within owner scope (e.g. AST-001)
 * @param name            human-readable asset name
 * @param description     optional descriptive notes
 * @param assetType       LAND, BUILDING, VEHICLE, EQUIPMENT, FURNITURE, OTHER
 * @param locationId      optional branch / Coop City location reference (null for entity-wide)
 * @param acquisitionDate optional date of acquisition
 * @param acquisitionCost optional historical acquisition cost in LKR
 */
public record RegisterAsset(
        String assetCode,
        String name,
        String description,
        String assetType,
        UUID locationId,
        LocalDate acquisitionDate,
        BigDecimal acquisitionCost) {}
