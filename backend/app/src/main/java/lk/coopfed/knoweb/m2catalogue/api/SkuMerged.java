package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a LOCAL SKU is merged into a target SHARED SKU (22A section 6; doc 22 section 6.3).
 */
public record SkuMerged(
        UUID sourceSkuId,
        UUID canonicalSkuId,
        UUID originEntityId,
        String sourceSkuCode,
        String canonicalSkuCode,
        String status)
        implements DomainEvent {

    public static final String TYPE = "sku.merged.v1";
}
