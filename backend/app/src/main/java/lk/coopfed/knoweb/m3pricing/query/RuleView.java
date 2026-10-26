package lk.coopfed.knoweb.m3pricing.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;

/** One discount rule, including advisory adoption metadata. */
public record RuleView(
        UUID ruleId,
        UUID ownerEntityId,
        String name,
        String kind,
        RulePredicate predicate,
        RuleBenefit benefit,
        int priority,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        Instant createdAt,
        boolean advisory,
        UUID adoptedFromRuleId) {}
