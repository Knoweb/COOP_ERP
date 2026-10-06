package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** discount_rule.activated.v1 (23A section 7): the rule applies at the owner's shops from validFrom. */
public record RuleActivated(UUID ruleId, UUID ownerEntityId, String kind, LocalDate validFrom, LocalDate validTo)
        implements DomainEvent {

    public static final String TYPE = "discount_rule.activated.v1";
}
