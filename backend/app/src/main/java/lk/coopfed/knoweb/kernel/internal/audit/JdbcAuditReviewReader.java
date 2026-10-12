package lk.coopfed.knoweb.kernel.internal.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.AuditReviewRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.ScopedExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAuditReviewReader implements AuditReviewReader {

    private final JdbcTemplate jdbc;
    private final ScopedExecution execution;

    public JdbcAuditReviewReader(JdbcTemplate jdbc, ScopedExecution execution) {
        this.jdbc = jdbc;
        this.execution = execution;
    }

    @Override
    public List<AuditReviewRecord> listReviews(String kind, Integer limit, ScopeContext scope) {
        if (scope != null) {
            return execution.inScope(scope, () -> doListReviews(kind, limit));
        }
        return doListReviews(kind, limit);
    }

    private List<AuditReviewRecord> doListReviews(String kind, Integer limit) {
        return jdbc.query(
                "select audit_id, event_type_code, occurred_at, owner_entity_id, subject_id, after_state "
                        + "from kernel.list_audit_reviews(?, ?)",
                (rs, rowNum) -> new AuditReviewRecord(
                        rs.getObject("audit_id", UUID.class),
                        rs.getString("event_type_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("subject_id", UUID.class),
                        rs.getString("after_state")),
                kind,
                limit != null ? limit : 100);
    }

    @Override
    public boolean reviewExists(
            String eventType, UUID subjectId, String detailKey, String detailValue, ScopeContext scope) {
        if (scope != null) {
            return execution.inScope(scope, () -> doReviewExists(eventType, subjectId, detailKey, detailValue));
        }
        return doReviewExists(eventType, subjectId, detailKey, detailValue);
    }

    private boolean doReviewExists(String eventType, UUID subjectId, String detailKey, String detailValue) {
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
