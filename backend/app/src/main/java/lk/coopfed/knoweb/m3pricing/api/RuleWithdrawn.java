package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** discount_rule.withdrawn.v1 (23A section 7): the rule no longer applies anywhere. */
public record RuleWithdrawn(UUID ruleId, UUID ownerEntityId, String kind) implements DomainEvent {

    public static final String TYPE = "discount_rule.withdrawn.v1";
}
