package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.RuleWithdrawn;
import lk.coopfed.knoweb.m3pricing.api.WithdrawRule;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WithdrawRule (23A section 7; doc 23 section 4.2). Guards: the owner in an entity-wide OWN
 * scope; the rule is one of the caller's and ACTIVE; a reason. Mutation: WITHDRAWN. The reason is
 * in the audit record (23A's table has no column for it). Event: discount_rule.withdrawn.v1.
 */
@Service
@CommandHandler(permission = "prc.rule.activate")
public class WithdrawRuleHandler implements Handles<WithdrawRule, UUID> {

    static final String AUDIT_WITHDRAWN = "RULE_WITHDRAWN";

    private final JdbcTemplate jdbc;
    private final RuleStore store;
    private final AuditFacade audit;
    private final EventPublisher events;

    WithdrawRuleHandler(JdbcTemplate jdbc, RuleStore store, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(WithdrawRule command, ScopeContext scope) {
        if (command == null || command.ruleId() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        RuleView rule = RuleGuards.ownRule(store, command.ruleId(), scope);
        if (!RuleStore.ACTIVE.equals(rule.status())) {
            throw new ProblemException("m3.rule.not_active");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "reason"));
        }

        jdbc.update("update pricing.discount_rule set status = 'WITHDRAWN' where rule_id = ?", rule.ruleId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RuleStore.WITHDRAWN);
        after.put("reason", command.reason().strip());
        audit.record(
                AUDIT_WITHDRAWN,
                Subject.of("discount_rule", rule.ruleId()),
                Map.of("status", RuleStore.ACTIVE),
                after,
                scope);

        events.publish(new RuleWithdrawn(rule.ruleId(), rule.ownerEntityId(), rule.kind()));
        return rule.ruleId();
    }
}
