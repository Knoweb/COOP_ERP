package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * Command to promote a LOCAL SKU to SHARED (22A section 6; doc 22 section 6.3).
 */
public record PromoteToShared(UUID skuId) {}
