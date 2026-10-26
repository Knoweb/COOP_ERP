package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.sql.Date;
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
import lk.coopfed.knoweb.m3pricing.api.AmendDraftRule;
import lk.coopfed.knoweb.m3pricing.api.RuleAmended;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Amend an existing DRAFT pricing rule (PRC-04). */
@Service
@CommandHandler(permission = "prc.rule.author")
public class AmendDraftRuleHandler implements Handles<AmendDraftRule, UUID> {

    static final String AUDIT_AMENDED = "RULE_AUTHORED";

    private final JdbcTemplate jdbc;
    private final RuleStore store;
    private final RuleVocabulary vocabulary;
    private final AuditFacade audit;
    private final EventPublisher events;

    AmendDraftRuleHandler(
            JdbcTemplate jdbc, RuleStore store, RuleVocabulary vocabulary, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.vocabulary = vocabulary;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendDraftRule command, ScopeContext scope) {

        if (command == null || command.ruleId() == null) {
            throw new ProblemException("request.invalid");
        }

        PriceListRules.requireOwnerScope(scope);

        RuleView rule = RuleGuards.ownRule(store, command.ruleId(), scope);

        if (!RuleStore.DRAFT.equals(rule.status())) {
            throw new ProblemException("m3.rule.not_draft");
        }

        if (command.name() == null || command.name().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "name"));
        }

        if (command.validFrom() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "validFrom"));
        }

        if (command.validTo() != null && command.validTo().isBefore(command.validFrom())) {
            throw new ProblemException("m3.rule.validity_invalid");
        }

        int priority = command.priority() == null ? AuthorRuleHandler.DEFAULT_PRIORITY : command.priority();

        if (priority < 0 || priority > Short.MAX_VALUE) {
            throw new ProblemException("m3.rule.priority_invalid");
        }

        vocabulary.check(command.kind(), command.predicate(), command.benefit(), scope);

        String name = command.name().strip();

        int updated = jdbc.update(
                "update pricing.discount_rule set "
                        + "name = ?, kind = ?, predicate = ?::jsonb, "
                        + "benefit = ?::jsonb, priority = ?, "
                        + "valid_from = ?, valid_to = ? "
                        + "where rule_id = ? and owner_entity_id = ? "
                        + "and status = 'DRAFT'",
                name,
                command.kind(),
                store.predicateJson(command.predicate()),
                store.benefitJson(command.benefit()),
                priority,
                Date.valueOf(command.validFrom()),
                command.validTo() == null ? null : Date.valueOf(command.validTo()),
                rule.ruleId(),
                scope.entityId());

        if (updated != 1) {
            throw new ProblemException("m3.rule.not_draft");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", rule.name());
        before.put("kind", rule.kind());
        before.put("priority", rule.priority());
        before.put("validFrom", rule.validFrom());
        before.put("validTo", rule.validTo());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("kind", command.kind());
        after.put("priority", priority);
        after.put("validFrom", command.validFrom());
        after.put("validTo", command.validTo());

        audit.record(AUDIT_AMENDED, Subject.of("discount_rule", rule.ruleId()), before, after, scope);

        events.publish(new RuleAmended(rule.ruleId(), rule.ownerEntityId(), command.kind()));

        return rule.ruleId();
    }
}
