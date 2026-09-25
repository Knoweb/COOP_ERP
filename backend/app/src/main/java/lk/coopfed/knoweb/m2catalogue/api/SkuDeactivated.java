package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record SkuDeactivated(UUID skuId, UUID ownerEntityId, String skuCode, String priorStatus)
        implements DomainEvent {

    public static final String TYPE = "sku.deactivated.v1";
}
