package lk.coopfed.knoweb.m8reporting.api;

import java.util.UUID;

/**
 * Command to create a profit center reporting dimension (Polgolla M1-08).
 *
 * @param code       unique code within owner scope (e.g. AGY-01, PACK-01)
 * @param name       human-readable name
 * @param category   AGENCY, PACKING, RETAIL, GENERAL
 * @param locationId optional operational location reference
 */
public record CreateProfitCenter(String code, String name, String category, UUID locationId) {}
