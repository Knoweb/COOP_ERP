package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * mrp_policy.changed.v1 (23A section 7): the policy of a SKU for an owner. Consumers: the snapshot
 * (M3-09) and M2, which keeps sku.multi_mrp_policy as the denormalised effective value (doc 23
 * section 3.4; that consumer is deferred).
 */
public record MrpPolicyChanged(
        UUID policyId, UUID ownerEntityId, UUID skuId, String policy, BigDecimal gapAmount, BigDecimal gapPercent)
        implements DomainEvent {

    public static final String TYPE = "mrp_policy.changed.v1";
}
