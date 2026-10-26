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
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m3pricing.api.AdoptAdvisoryRule;
import lk.coopfed.knoweb.m3pricing.api.RuleDrafted;
import lk.coopfed.knoweb.m3pricing.internal.list.Federation;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adopt a Federation advisory rule into an MPCS-owned draft. */
@Service
@CommandHandler(permission = "prc.rule.author")
public class AdoptAdvisoryRuleHandler implements Handles<AdoptAdvisoryRule, UUID> {

    private final JdbcTemplate jdbc;
    private final RuleStore store;
    private final PartyQueries party;
    private final Federation federation;
    private final RuleVocabulary vocabulary;
    private final AuditFacade audit;
    private final EventPublisher events;

    AdoptAdvisoryRuleHandler(
            JdbcTemplate jdbc,
            RuleStore store,
            PartyQueries party,
            Federation federation,
            RuleVocabulary vocabulary,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.party = party;
        this.federation = federation;
        this.vocabulary = vocabulary;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AdoptAdvisoryRule command, ScopeContext scope) {
        if (command == null || command.sourceRuleId() == null) {
            throw new ProblemException("request.invalid");
        }

        PriceListRules.requireOwnerScope(scope);

        boolean society = party.getEntity(scope.entityId(), scope)
                .map(entity -> "MPCS".equals(entity.entityType()))
                .orElse(false);

        if (!society) {
            throw new ProblemException("m3.rule.adopt_mpcs_only");
        }

        UUID federationId = federation.entityId().orElseThrow(() -> new ProblemException("m3.rule.advisory_not_found"));

        RuleView source = store.find(command.sourceRuleId())
                .filter(rule -> rule.ownerEntityId().equals(federationId))
                .filter(RuleView::advisory)
                .filter(rule -> RuleStore.ACTIVE.equals(rule.status()))
                .orElseThrow(() -> new ProblemException("m3.rule.advisory_not_found"));

        vocabulary.check(source.kind(), source.predicate(), source.benefit(), scope);

        UUID newId = Ids.next();

        jdbc.update(
                "insert into pricing.discount_rule "
                        + "(rule_id, owner_entity_id, name, kind, predicate, benefit,"
                        + " priority, advisory, adopted_from_rule_id, valid_from, valid_to, status)"
                        + " values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, false, ?, ?, ?, 'DRAFT')",
                newId,
                scope.entityId(),
                source.name(),
                source.kind(),
                store.predicateJson(source.predicate()),
                store.benefitJson(source.benefit()),
                source.priority(),
                source.ruleId(),
                Date.valueOf(source.validFrom()),
                source.validTo() == null ? null : Date.valueOf(source.validTo()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ruleId", newId);
        after.put("adoptedFromRuleId", source.ruleId());
        after.put("ownerEntityId", scope.entityId());
        after.put("kind", source.kind());
        after.put("status", RuleStore.DRAFT);

        audit.record("RULE_AUTHORED", Subject.of("discount_rule", newId), null, after, scope);

        events.publish(new RuleDrafted(newId, scope.entityId(), source.kind()));

        return newId;
    }
}
