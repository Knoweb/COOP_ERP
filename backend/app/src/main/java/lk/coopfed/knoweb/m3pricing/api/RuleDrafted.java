package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * discount_rule.drafted.v1: a rule was authored as a DRAFT. Doc 23 section 5.1 names no event for
 * AuthorRule; the build requires every handler to publish one (AGENTS.md), as for
 * price_list.drafted.v1, and the M3 README records it.
 */
public record RuleDrafted(UUID ruleId, UUID ownerEntityId, String kind) implements DomainEvent {

    public static final String TYPE = "discount_rule.drafted.v1";
}
