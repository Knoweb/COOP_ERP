package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Amend an existing draft pricing rule.
 * PRC-04, 23A section 7.
 */
public record AmendDraftRule(
        UUID ruleId,
        String name,
        String kind,
        RulePredicate predicate,
        RuleBenefit benefit,
        Integer priority,
        LocalDate validFrom,
        LocalDate validTo) {}
