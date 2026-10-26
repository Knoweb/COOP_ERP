package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** discount_rule.expired.v1: the rule's validity has ended by the clock. */
public record RuleExpired(UUID ruleId, UUID ownerEntityId, String kind, LocalDate validTo) implements DomainEvent {

    public static final String TYPE = "discount_rule.expired.v1";
}
