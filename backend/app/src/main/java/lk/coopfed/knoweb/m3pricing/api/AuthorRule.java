package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;

/**
 * AuthorRule (23A section 7; doc 23 section 5.1): a discount rule of the caller's entity, as a
 * DRAFT. {@code priority} orders line rules under the stacking policy (lower first); null is the
 * default 100 of 23A section 3. {@code validTo} null: open-ended.
 */
public record AuthorRule(
        String name,
        String kind,
        RulePredicate predicate,
        RuleBenefit benefit,
        Integer priority,
        LocalDate validFrom,
        LocalDate validTo) {}
