package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What can be sold or allocated of one SKU at one location (doc 25 section 3.4): the GOOD lots
 * with stock, less what issued delivery notes have reserved and not dispatched. Never negative.
 */
public record Availability(UUID locationId, UUID skuId, BigDecimal available) {}
