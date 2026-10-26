package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m6pos.api.CashMovementRecorded;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordCashMovementHandler: ingests cash drops and pay-outs from tills (doc 26 §3.5; 26A §10).
 * Persists immutable facts into pos.cash_movement with RLS, applies and flags anomalies
 * (SESSION_UNKNOWN, SESSION_CLOSED, LOCATION_MISMATCH), handles exact replay idempotency,
 * audits recorded facts or differences, and publishes domain event CashMovementRecorded.
 */
@Service
@CommandHandler(permission = "pos.receipt.view")
public class RecordCashMovementHandler implements Handles<RecordCashMovement, UUID> {

    static final String AUDIT_RECORDED = "CASH_MOVEMENT_RECORDED";
    static final String AUDIT_FLAGGED = "CASH_MOVEMENT_FLAGGED";
    static final String AUDIT_REPLAY_DIFFERS = "CASH_MOVEMENT_REPLAY_DIFFERS";
    static final String MESSAGE_REPLAY_DIFFERS = "m6.cash_movement.replay_differs";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordCashMovementHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordCashMovement command, ScopeContext scope) {
        IngestGuards.requireDeviceScope(scope);
        if (command.movementId() == null
                || command.sessionId() == null
                || command.occurredAt() == null
                || command.kind() == null
                || command.amount() == null) {
            throw new ProblemException("m6.cash_movement.malformed");
        }
        if (command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProblemException("m6.cash_movement.invalid_amount");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m6.cash_movement.reason_required");
        }

        List<Map<String, Object>> existing = jdbc.queryForList(
                "select session_id, kind, amount, reason from pos.cash_movement where movement_id = ?",
                command.movementId());
        if (!existing.isEmpty()) {
            replayed(command, existing.get(0), scope);
            return command.movementId();
        }

        List<String> flags = new ArrayList<>();
        UUID shop = scope.locationId();
        if (command.locationId() != null && !command.locationId().equals(shop)) {
            flags.add("LOCATION_MISMATCH");
        }
        if (!sessionKnown(command.sessionId())) {
            flags.add("SESSION_UNKNOWN");
        } else if (closedBefore(command.sessionId(), command.occurredAt())) {
            flags.add("SESSION_CLOSED");
        }

        jdbc.update(
                """
                insert into pos.cash_movement (movement_id, owner_entity_id, location_id, till_position_id, device_id,
                    session_id, kind, amount, occurred_at, operator_user_id, reason, device_seq, flags)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[])
                """,
                command.movementId(),
                scope.entityId(),
                shop,
                command.tillPositionId(),
                scope.deviceId() != null ? scope.deviceId() : command.deviceId(),
                command.sessionId(),
                command.kind(),
                command.amount(),
                Timestamp.from(command.occurredAt()),
                command.operatorUserId(),
                command.reason().trim(),
                command.deviceSeq(),
                "{" + String.join(",", flags) + "}");

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kind", command.kind());
        after.put("amount", command.amount());
        after.put("reason", command.reason().trim());
        after.put("locationId", shop);
        after.put("flags", flags);

        audit.record(
                flags.isEmpty() ? AUDIT_RECORDED : AUDIT_FLAGGED,
                Subject.of("cash_movement", command.movementId()),
                null,
                after,
                scope);

        events.publish(new CashMovementRecorded(
                command.movementId(), command.sessionId(), scope.entityId(), shop, command.kind(), command.amount()));

        return command.movementId();
    }

    private void replayed(RecordCashMovement command, Map<String, Object> first, ScopeContext scope) {
        boolean same = Objects.equals(first.get("session_id"), command.sessionId())
                && Objects.equals(first.get("kind"), command.kind())
                && sameAmount((BigDecimal) first.get("amount"), command.amount())
                && Objects.equals(first.get("reason"), command.reason().trim());
        if (same) {
            return;
        }

        Map<String, Object> differs = new LinkedHashMap<>();
        differs.put("messageId", MESSAGE_REPLAY_DIFFERS);
        differs.put("movementId", command.movementId());
        differs.put("first", first);
        differs.put(
                "later",
                Map.of(
                        "sessionId", String.valueOf(command.sessionId()),
                        "kind", command.kind(),
                        "amount", command.amount(),
                        "reason", command.reason().trim()));
        differs.put("deviceId", scope.deviceId());

        audit.record(
                AUDIT_REPLAY_DIFFERS,
                Subject.of("cash_movement", command.movementId()),
                null,
                differs,
                scope,
                "The cash movement arrived again with other details; the first copy is kept");
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private boolean sessionKnown(UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.till_session where session_id = ?)", Boolean.class, sessionId));
    }

    private boolean closedBefore(UUID sessionId, Instant occurredAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.till_session_close where session_id = ? and closed_at < ?)",
                Boolean.class,
                sessionId,
                Timestamp.from(occurredAt)));
    }
}
