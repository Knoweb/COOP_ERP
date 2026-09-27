package lk.coopfed.knoweb.kernel.internal.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The audit rows a till recorded offline, written into the kernel's audit log as they happened
 * (doc 32 section 3.1: the {@code audit.*} family is "applied by the kernel audit store, insert
 * only"; doc 18 part D). Not {@link lk.coopfed.knoweb.kernel.api.AuditFacade}: the facade audits
 * what central does now, stamping the time and the caller; a till's row keeps its own id (UUIDv7
 * at the source device), the time it happened on the till, the operator who acted there, and the
 * device and sequence it came with, so that D-I4 ("(device_id, device_seq) is unique and dense per
 * device") can be read from the log.
 *
 * <p>Called by the sync gateway's event applier inside the ingestion chunk's transaction, under the
 * device's own scope (its entity, its shop), so the row commits with the cursor or not at all.
 */
@Component
public class DeviceAuditWriter {

    /** One audit row as the till recorded it. JSON states are text, already checked by the caller. */
    public record DeviceAudit(
            UUID auditId,
            String eventTypeCode,
            Instant occurredAt,
            LocalDateTime occurredLocal,
            UUID ownerEntityId,
            UUID locationId,
            UUID actorUserId,
            UUID deviceId,
            UUID tillPositionId,
            String subjectTable,
            UUID subjectId,
            UUID documentId,
            String beforeState,
            String afterState,
            String reasonCode,
            String reasonText,
            UUID witnessUserId,
            long deviceSeq,
            UUID correlationId) {}

    private final JdbcTemplate jdbc;

    public DeviceAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether a till may record this type offline: it is in the catalogue and marked
     * {@code offline_capturable} (doc 18 part D, "all till events are").
     */
    public boolean offlineCapturable(String eventTypeCode) {
        Boolean capturable = jdbc.queryForObject(
                """
                select exists (select 1 from kernel.audit_event_type
                                where event_type_code = ? and offline_capturable)
                """,
                Boolean.class,
                eventTypeCode);
        return Boolean.TRUE.equals(capturable);
    }

    /** Inserts the row, in the caller's transaction. */
    public void write(DeviceAudit row) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A till's audit row is written inside the ingestion transaction");
        }
        jdbc.update(
                """
                insert into kernel.audit_event (
                    audit_id, event_type_code, occurred_at, occurred_local, owner_entity_id, location_id,
                    actor_user_id, device_id, till_position_id, subject_table, subject_id, document_id,
                    before_state, after_state, reason_code, reason_text, witness_user_id, device_seq,
                    correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?)
                """,
                row.auditId(),
                row.eventTypeCode(),
                Timestamp.from(row.occurredAt()),
                row.occurredLocal() == null ? null : Timestamp.valueOf(row.occurredLocal()),
                row.ownerEntityId(),
                row.locationId(),
                row.actorUserId(),
                row.deviceId(),
                row.tillPositionId(),
                row.subjectTable(),
                row.subjectId(),
                row.documentId(),
                row.beforeState(),
                row.afterState(),
                row.reasonCode(),
                row.reasonText(),
                row.witnessUserId(),
                row.deviceSeq(),
                row.correlationId());
    }
}
