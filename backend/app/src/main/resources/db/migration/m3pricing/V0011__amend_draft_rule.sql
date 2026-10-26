
-- PRC-04: Allow authorised command handlers to amend draft pricing rules.
-- AmendDraftRuleHandler checks ownership and DRAFT status before updating.

GRANT UPDATE (
    name,
    kind,
    predicate,
    benefit,
    priority,
    valid_from,
    valid_to
) ON pricing.discount_rule TO app_rw;
