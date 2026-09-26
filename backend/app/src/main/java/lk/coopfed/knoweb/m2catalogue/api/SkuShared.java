package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A DRAFT SKU of the Federation entered the shared catalogue (22A section 6, ActivateSharedSku). */
public record SkuShared(UUID skuId, UUID ownerEntityId, String skuCode, String status) implements DomainEvent {

    public static final String TYPE = "sku.shared.v1";
}
