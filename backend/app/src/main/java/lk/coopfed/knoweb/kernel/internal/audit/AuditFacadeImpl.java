package lk.coopfed.knoweb.kernel.internal.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AuditFacadeImpl implements AuditFacade {

    private static final Pattern EVENT_TYPE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditFacadeImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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

        validateRequest(eventType, subject, scope);

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("audit.record("
                    + eventType
                    + ") was called outside a transaction; "
                    + "call it inside the handler's @Transactional method, "
                    + "after the mutation");
        }

        Boolean catalogueType = jdbc.queryForObject(
                """
                select exists (
                    select 1
                      from kernel.audit_event_type
                     where event_type_code = ?
                )
                """,
                Boolean.class,
                eventType);

        if (!Boolean.TRUE.equals(catalogueType)) {
            throw new IllegalArgumentException(
                    "Audit event type is not present in " + "kernel.audit_event_type catalogue: " + eventType);
        }

        AuditDiff diff = minimalDiff(before, after);

        UUID documentId = "document".equalsIgnoreCase(subject.type()) ? subject.id() : null;

        String reasonText = reason == null || reason.isBlank() ? null : reason;

        jdbc.update(
                """
                insert into kernel.audit_event (
                    audit_id,
                    event_type_code,
                    occurred_at,
                    owner_entity_id,
                    location_id,
                    actor_user_id,
                    device_id,
                    subject_table,
                    subject_id,
                    document_id,
                    before_state,
                    after_state,
                    reason_text,
                    witness_user_id,
                    correlation_id
                )
                values (
                    gen_random_uuid(),
                    ?,
                    now(),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    cast(? as jsonb),
                    cast(? as jsonb),
                    ?,
                    ?,
                    ?
                )
                """,
                eventType,
                scope.entityId(),
                scope.locationId(),
                scope.userId(),
                scope.deviceId(),
                subject.type(),
                subject.id(),
                documentId,
                json(diff.beforeState()),
                json(diff.afterState()),
                reasonText,
                witnessUserId,
                scope.correlationId());
    }

    private static void validateRequest(String eventType, Subject subject, ScopeContext scope) {

        if (eventType == null || !EVENT_TYPE_CODE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("Audit event type must be a catalogue code "
                    + "(capitals, digits, underscores; at most 40 characters): "
                    + eventType);
        }

        if (subject == null || subject.type() == null || subject.type().isBlank() || subject.id() == null) {
            throw new IllegalArgumentException("Audit record " + eventType + " needs a subject with a type and an id");
        }

        if (subject.type().length() > 40) {
            throw new IllegalArgumentException("Audit subject type must be at most 40 characters");
        }

        if (scope == null || !scope.hasActiveScope()) {
            throw new IllegalArgumentException("Audit record " + eventType + " needs a scope with an active entity");
        }

        if (scope.entityId() == null) {
            throw new IllegalArgumentException("Audit record " + eventType + " needs an owner entity");
        }

        if (scope.correlationId() == null) {
            throw new IllegalArgumentException("Audit record " + eventType + " needs a correlation id");
        }
    }

    AuditDiff minimalDiff(Object before, Object after) {

        JsonNode beforeNode = before == null ? null : objectMapper.valueToTree(before);

        JsonNode afterNode = after == null ? null : objectMapper.valueToTree(after);

        if (Objects.equals(beforeNode, afterNode)) {
            return new AuditDiff(null, null);
        }

        if (beforeNode == null || afterNode == null) {
            return new AuditDiff(beforeNode, afterNode);
        }

        if (!beforeNode.isObject() || !afterNode.isObject()) {
            return new AuditDiff(beforeNode, afterNode);
        }

        Set<String> columns = new LinkedHashSet<>();
        beforeNode.fieldNames().forEachRemaining(columns::add);
        afterNode.fieldNames().forEachRemaining(columns::add);

        ObjectNode beforeDiff = objectMapper.createObjectNode();
        ObjectNode afterDiff = objectMapper.createObjectNode();

        for (String column : columns) {
            JsonNode oldValue = beforeNode.get(column);
            JsonNode newValue = afterNode.get(column);

            if (Objects.equals(oldValue, newValue)) {
                continue;
            }

            if (oldValue != null) {
                beforeDiff.set(column, oldValue);
            }

            if (newValue != null) {
                afterDiff.set(column, newValue);
            }
        }

        return new AuditDiff(beforeDiff.isEmpty() ? null : beforeDiff, afterDiff.isEmpty() ? null : afterDiff);
    }

    private static String json(JsonNode value) {
        return value == null ? null : value.toString();
    }

    record AuditDiff(JsonNode beforeState, JsonNode afterState) {}
}
