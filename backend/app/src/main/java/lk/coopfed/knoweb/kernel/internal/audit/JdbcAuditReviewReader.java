package lk.coopfed.knoweb.kernel.internal.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.AuditReviewRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two definer functions of kernel V0093. The methods are public, transactional and take the
 * caller's scope, which is what the kernel's scope aspect puts on the session: the functions
 * test the class themselves, so a caller cannot widen what it reads by passing another scope
 * than its own (only a {@code @ScheduledJob} holds another entity's scope; CR-19A-7).
 */
@Component
public class JdbcAuditReviewReader implements AuditReviewReader {

    private final JdbcTemplate jdbc;

    public JdbcAuditReviewReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditReviewRecord> listReviews(Set<String> eventTypes, int limit, ScopeContext scope) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                connection -> {
                    var statement = connection.prepareStatement(
                            "select audit_id, event_type_code, occurred_at, owner_entity_id, subject_id, after_state"
                                    + " from kernel.list_audit_reviews(?, ?)");
                    statement.setArray(1, connection.createArrayOf("text", eventTypes.toArray()));
                    statement.setInt(2, limit);
                    return statement;
                },
                (rs, rowNum) -> new AuditReviewRecord(
                        rs.getObject("audit_id", UUID.class),
                        rs.getString("event_type_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("subject_id", UUID.class),
                        rs.getString("after_state")));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean reviewExists(
            String eventType, UUID subjectId, String detailKey, String detailValue, ScopeContext scope) {
        Boolean exists = jdbc.queryForObject(
                "select kernel.audit_review_exists(?, ?, ?, ?)",
                Boolean.class,
                eventType,
                subjectId,
                detailKey,
                detailValue);
        return Boolean.TRUE.equals(exists);
    }
}
