package lk.coopfed.knoweb.kernel.internal.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAuditFacade implements AuditFacade {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAuditFacade(JdbcTemplate jdbc, ObjectMapper mapper) {

        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void record(
            String eventType,
            Subject subject,
            Object before,
            Object after,
            ScopeContext scope,
            String reason,
            UUID witnessUserId) {

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Audit event type must not be blank");
        }

        if (subject == null) {
            throw new IllegalArgumentException("Audit subject must not be null");
        }

        jdbc.update(
                """
                insert into kernel.audit_log (
                    audit_id,
                    event_type,
                    subject_json,
                    before_json,
                    after_json,
                    scope_json,
                    reason,
                    witness_user_id,
                    created_at
                )
                values (
                    ?,
                    ?,
                    cast(? as jsonb),
                    cast(? as jsonb),
                    cast(? as jsonb),
                    cast(? as jsonb),
                    ?,
                    ?,
                    now()
                )
                """,
                UUID.randomUUID(),
                eventType.strip(),
                json(subject),
                nullableJson(before),
                nullableJson(after),
                nullableJson(scope),
                blankToNull(reason),
                witnessUserId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize audit payload", ex);
        }
    }

    private String nullableJson(Object value) {
        return value == null ? null : json(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
