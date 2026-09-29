package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.NotificationRuleChanged;
import lk.coopfed.knoweb.m9integration.api.SetNotificationRuleStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ActivateRule / RetireRule (29A section 6) for the federation-wide rules, the toggle of the rules
 * screen. DefineRule and the entity's own rules come with their ticket.
 *
 * <p>Guards, in order: a user of the Federation in its OWN scope ({@code m9.rule.federation_required};
 * "F for federation rules", 29A section 3.1); ACTIVE or RETIRED ({@code m9.rule.status_invalid});
 * a federation-wide rule ({@code m9.rule.not_found}); another status than it has
 * ({@code m9.rule.status_unchanged}); to activate, its template ACTIVE
 * ({@code m9.rule.template_inactive}). Mutation: the status. Audit NOTIFICATION_RULE_STATUS_CHANGED;
 * event notification_rule.changed.v1, on which the kernel's dispatcher empties its rule cache.
 */
@Service
@CommandHandler(permission = "int.notify.manage")
class SetNotificationRuleStatusHandler implements Handles<SetNotificationRuleStatus, UUID> {

    static final String AUDIT_CHANGED = "NOTIFICATION_RULE_STATUS_CHANGED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final String federationEntityId;

    SetNotificationRuleStatusHandler(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            @Value("${coop-erp.system.entity-id:}") String federationEntityId) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federationEntityId = federationEntityId;
    }

    private record Rule(String eventType, String status, String templateStatus) {}

    @Override
    @Transactional
    public UUID handle(SetNotificationRuleStatus command, ScopeContext scope) {
        if (scope.userId() == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || !scope.entityId().toString().equals(federationEntityId)) {
            throw new ProblemException("m9.rule.federation_required");
        }
        if (command == null
                || !(SetNotificationRuleStatus.ACTIVE.equals(command.status())
                        || SetNotificationRuleStatus.RETIRED.equals(command.status()))) {
            throw new ProblemException("m9.rule.status_invalid");
        }
        List<Rule> found = jdbc.query(
                """
                select r.event_type, r.status, t.status as template_status
                  from integration.notification_rule r
                  join integration.notification_template t on t.template_id = r.template_id
                 where r.rule_id = ? and r.owner_entity_id is null
                 for update of r
                """,
                (rs, i) ->
                        new Rule(rs.getString("event_type"), rs.getString("status"), rs.getString("template_status")),
                command.ruleId());
        if (found.isEmpty()) {
            throw new ProblemException("m9.rule.not_found");
        }
        Rule rule = found.get(0);
        if (rule.status().equals(command.status())) {
            throw new ProblemException("m9.rule.status_unchanged", Map.of("status", command.status()));
        }
        if (SetNotificationRuleStatus.ACTIVE.equals(command.status()) && !"ACTIVE".equals(rule.templateStatus())) {
            throw new ProblemException("m9.rule.template_inactive");
        }

        jdbc.update(
                "update integration.notification_rule set status = ? where rule_id = ?",
                command.status(),
                command.ruleId());

        audit.record(
                AUDIT_CHANGED,
                Subject.of("notification_rule", command.ruleId()),
                Map.of("status", rule.status()),
                Map.of("status", command.status()),
                scope);
        events.publish(new NotificationRuleChanged(command.ruleId(), rule.eventType(), command.status()));
        return command.ruleId();
    }
}
