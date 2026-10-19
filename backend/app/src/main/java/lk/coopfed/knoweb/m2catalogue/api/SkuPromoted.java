package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a LOCAL SKU is promoted to SHARED under Federation ownership (22A section 6).
 */
public record SkuPromoted(UUID skuId, UUID originEntityId, String skuCode, String status) implements DomainEvent {

    public static final String TYPE = "sku.promoted.v1";
}
