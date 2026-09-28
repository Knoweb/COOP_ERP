package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.RuleActivated;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ActivateRule (23A section 7; doc 23 section 4.2). Guards: the owner in an entity-wide OWN
 * scope; the rule is one of the caller's and a DRAFT; valid_from is today or later; the
 * vocabulary again (the item may have been deactivated since it was authored). Mutation: ACTIVE.
 * Event: discount_rule.activated.v1.
 */
@Service
@CommandHandler(permission = "prc.rule.activate")
public class ActivateRuleHandler implements Handles<ActivateRule, UUID> {

    static final String AUDIT_ACTIVATED = "RULE_ACTIVATED";

    private final JdbcTemplate jdbc;
    private final RuleStore store;
    private final RuleVocabulary vocabulary;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    ActivateRuleHandler(
            JdbcTemplate jdbc,
            RuleStore store,
            RuleVocabulary vocabulary,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.vocabulary = vocabulary;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public UUID handle(ActivateRule command, ScopeContext scope) {
        if (command == null || command.ruleId() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        RuleView rule = RuleGuards.ownRule(store, command.ruleId(), scope);
        if (!RuleStore.DRAFT.equals(rule.status())) {
            throw new ProblemException("m3.rule.not_draft");
        }
        LocalDate today = PriceListRules.today(clock, businessZone);
        if (rule.validFrom().isBefore(today)) {
            throw new ProblemException("m3.rule.valid_from_past");
        }
        vocabulary.check(rule.kind(), rule.predicate(), rule.benefit(), scope);

        jdbc.update("update pricing.discount_rule set status = 'ACTIVE' where rule_id = ?", rule.ruleId());

        audit.record(
                AUDIT_ACTIVATED,
                Subject.of("discount_rule", rule.ruleId()),
                Map.of("status", RuleStore.DRAFT),
                Map.of("status", RuleStore.ACTIVE, "validFrom", rule.validFrom()),
                scope);

        events.publish(
                new RuleActivated(rule.ruleId(), rule.ownerEntityId(), rule.kind(), rule.validFrom(), rule.validTo()));
        return rule.ruleId();
    }
}
