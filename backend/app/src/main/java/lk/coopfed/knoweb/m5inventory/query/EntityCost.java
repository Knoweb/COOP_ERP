package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.UUID;

/** The entity's moving weighted average of one SKU and the quantity it holds (doc 25 section 3.3). */
public record EntityCost(UUID ownerEntityId, UUID skuId, BigDecimal qtyOnHand, BigDecimal avgCost) {}
