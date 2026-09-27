package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/** Retires one of the caller's own images of a SKU, PENDING or ACTIVE (22A section 5, retireImage). */
public record RetireImage(UUID skuId, UUID imageId) {}
