package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * Command to merge a LOCAL SKU into an existing SHARED SKU (22A section 6; doc 22 section 6.3).
 */
public record MergeIntoShared(UUID sourceSkuId, UUID targetSkuId) {}
