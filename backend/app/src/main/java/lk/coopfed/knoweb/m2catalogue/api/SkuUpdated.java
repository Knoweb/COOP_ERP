package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record SkuUpdated(UUID skuId, UUID ownerEntityId, String skuCode, String status) implements DomainEvent {

    public static final String TYPE = "sku.updated.v1";
}
