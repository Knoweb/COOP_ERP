package lk.coopfed.knoweb.m9integration.internal.notify;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NotificationRuleQueries for the kernel's dispatcher and renderer (29A section 4, "NotificationRuleQueries
 * (for the kernel dispatcher)"), over {@code notification_rule} and {@code notification_template}.
 *
 * <p>Not @Transactional on purpose: the dispatcher asks inside the consumer's transaction, whose
 * connection already carries the event owner's scope, and the renderer asks after the commit
 * with none. Both tables are reference data every session reads (V0001), so the answer does not
 * depend on the scope, and a transaction of its own here would reset the consumer's.
 *
 * <p>The audience spec is stored as JSON (29A section 3); the kernel's contract takes one string:
 * the role code for the role kinds, the payload field for EXPLICIT and CUSTOMER.
 */
class RuleStore implements NotificationRuleQueries {

    private final JdbcTemplate jdbc;

    RuleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NotificationRule> activeRules(String eventType, UUID ownerEntityId) {
        return jdbc.query(
                """
                select rule_id, owner_entity_id, event_type, predicate::text as predicate, template_id,
                       audience_kind,
                       coalesce(audience_spec ->> 'role', audience_spec ->> 'field',
                                audience_spec ->> 'from_payload') as spec,
                       channels, priority
                  from integration.notification_rule
                 where event_type = ? and status = 'ACTIVE'
                   and (owner_entity_id is null or owner_entity_id = ?)
                 order by priority, rule_id
                """,
                (rs, i) -> new NotificationRule(
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("predicate"),
                        rs.getString("template_id"),
                        AudienceKind.valueOf(rs.getString("audience_kind")),
                        rs.getString("spec"),
                        texts(rs, "channels"),
                        rs.getInt("priority")),
                eventType,
                ownerEntityId);
    }

    /** A template in any status: a notification queued before a template was retired still renders. */
    @Override
    public Optional<NotificationTemplate> template(String templateId) {
        return jdbc
                .query(
                        """
                        select template_id, channel, subject_en, subject_si, subject_ta, body_en, body_si, body_ta
                          from integration.notification_template where template_id = ?
                        """,
                        (rs, i) -> new NotificationTemplate(
                                rs.getString("template_id"),
                                rs.getString("channel"),
                                rs.getString("subject_en"),
                                rs.getString("subject_si"),
                                rs.getString("subject_ta"),
                                rs.getString("body_en"),
                                rs.getString("body_si"),
                                rs.getString("body_ta")),
                        templateId)
                .stream()
                .findFirst();
    }

    static List<String> texts(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
