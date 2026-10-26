package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when a draft pricing rule is amended.
 */
public record RuleAmended(UUID ruleId, UUID ownerEntityId, String kind) implements DomainEvent {

    public static final String TYPE = "discount_rule.amended.v1";
}
