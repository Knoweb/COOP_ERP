package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.RuleDrafted;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthorRule (23A section 7; doc 23 section 4.2). Guards: the owner in an entity-wide OWN scope
 * (a society authors the rules of its shops and funds them, G-06); a name; the validity (from a
 * date, to a date not before it); a priority; the kind's vocabulary (RuleVocabulary: the item
 * exists and is active, an expiry markdown needs an expiry-tracked item, the benefit fits the
 * kind). Mutation: a DRAFT rule. Advisory rules and AdoptAdvisoryRule are deferred for the demo.
 */
@Service
@CommandHandler(permission = "prc.rule.author")
public class AuthorRuleHandler implements Handles<AuthorRule, UUID> {

    static final String AUDIT_AUTHORED = "RULE_AUTHORED";

    /** 23A section 3: priority smallint NOT NULL DEFAULT 100. */
    static final int DEFAULT_PRIORITY = 100;

    private final JdbcTemplate jdbc;
    private final RuleStore store;
    private final RuleVocabulary vocabulary;
    private final AuditFacade audit;
    private final EventPublisher events;

    AuthorRuleHandler(
            JdbcTemplate jdbc, RuleStore store, RuleVocabulary vocabulary, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.vocabulary = vocabulary;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AuthorRule command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        if (command.name() == null || command.name().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "name"));
        }
        if (command.validFrom() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "validFrom"));
        }
        if (command.validTo() != null && command.validTo().isBefore(command.validFrom())) {
            throw new ProblemException("m3.rule.validity_invalid");
        }
        int priority = command.priority() == null ? DEFAULT_PRIORITY : command.priority();
        if (priority < 0 || priority > Short.MAX_VALUE) {
            throw new ProblemException("m3.rule.priority_invalid");
        }
        vocabulary.check(command.kind(), command.predicate(), command.benefit(), scope);

        UUID ruleId = Ids.next();
        String name = command.name().strip();
        jdbc.update(
                "insert into pricing.discount_rule (rule_id, owner_entity_id, name, kind, predicate, benefit,"
                        + " priority, valid_from, valid_to, status) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,"
                        + " 'DRAFT')",
                ruleId,
                scope.entityId(),
                name,
                command.kind(),
                store.predicateJson(command.predicate()),
                store.benefitJson(command.benefit()),
                priority,
                Date.valueOf(command.validFrom()),
                command.validTo() == null ? null : Date.valueOf(command.validTo()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ruleId", ruleId);
        after.put("name", name);
        after.put("kind", command.kind());
        after.put(
                "benefit",
                command.benefit().kind() + " " + command.benefit().value().toPlainString());
        after.put("priority", priority);
        after.put("validFrom", command.validFrom());
        after.put("validTo", command.validTo());
        after.put("status", RuleStore.DRAFT);
        audit.record(AUDIT_AUTHORED, Subject.of("discount_rule", ruleId), null, after, scope);

        events.publish(new RuleDrafted(ruleId, scope.entityId(), command.kind()));
        return ruleId;
    }
}
