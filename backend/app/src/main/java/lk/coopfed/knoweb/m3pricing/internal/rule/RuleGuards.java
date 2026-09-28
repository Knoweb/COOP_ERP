package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.query.RuleView;

/** Guards the rule handlers share. */
final class RuleGuards {

    private RuleGuards() {}

    /** The rule, when it is one of the caller's entity; another entity's rule is "not found". */
    static RuleView ownRule(RuleStore store, UUID ruleId, ScopeContext scope) {
        return store.find(ruleId)
                .filter(rule -> rule.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.rule.not_found"));
    }
}
