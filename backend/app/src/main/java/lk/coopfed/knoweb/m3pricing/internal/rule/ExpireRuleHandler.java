package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
import lk.coopfed.knoweb.m3pricing.api.ExpireRule;
import lk.coopfed.knoweb.m3pricing.api.RuleExpired;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ExpireRule: ACTIVE to EXPIRED by the clock (doc 23 section 4.2; doc 03 section 12), sent by
 * {@link RuleExpiryJob} in the rule owner's OWN scope. Guards: the owner in an entity-wide OWN
 * scope; the rule is one of the caller's and ACTIVE; its validity has passed (valid_to is not null
 * and valid_to &lt; today). Mutation: EXPIRED. Event: discount_rule.expired.v1.
 */
@Service
@CommandHandler(permission = "prc.rule.activate")
public class ExpireRuleHandler implements Handles<ExpireRule, UUID> {

    public static final String AUDIT_EXPIRED = "RULE_EXPIRED";

    private final JdbcTemplate jdbc;
    private final RuleStore store;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    ExpireRuleHandler(
            JdbcTemplate jdbc,
            RuleStore store,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public UUID handle(ExpireRule command, ScopeContext scope) {
        if (command == null || command.ruleId() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        RuleView rule = RuleGuards.ownRule(store, command.ruleId(), scope);
        if (!RuleStore.ACTIVE.equals(rule.status())) {
            throw new ProblemException(
                    "m3.rule.not_active", Map.of("ruleId", command.ruleId(), "status", rule.status()));
        }
        LocalDate today = PriceListRules.today(clock, businessZone);
        if (rule.validTo() == null || !rule.validTo().isBefore(today)) {
            throw new ProblemException("m3.rule.not_due", Map.of("ruleId", command.ruleId()));
        }

        int updated = jdbc.update(
                "update pricing.discount_rule set status = 'EXPIRED' where rule_id = ? and status = 'ACTIVE'",
                rule.ruleId());
        if (updated != 1) {
            throw new ProblemException("m3.rule.not_active", Map.of("ruleId", command.ruleId()));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RuleStore.EXPIRED);
        after.put("validTo", rule.validTo());
        audit.record(
                AUDIT_EXPIRED,
                Subject.of("discount_rule", rule.ruleId()),
                Map.of("status", RuleStore.ACTIVE),
                after,
                scope);

        events.publish(new RuleExpired(rule.ruleId(), rule.ownerEntityId(), rule.kind(), rule.validTo()));
        return rule.ruleId();
    }
}
