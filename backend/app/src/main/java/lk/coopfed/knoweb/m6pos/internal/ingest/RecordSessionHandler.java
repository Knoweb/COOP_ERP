package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m6pos.api.TillSessionRecorded;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordSession: the minimal SessionHook of 26A section 10. The open is a {@code till_session}
 * row with the float; the close a {@code till_session_close} row of its own with the counted and
 * expected cash and the variance the till worked out (a blind close, doc 26 section 4.2). Nothing
 * is updated. A close whose open never arrived is still recorded (a fact is not refused).
 *
 * <p>Guards: the device's OWN scope at its shop ({@code m6.scope.device_required}); a session id
 * and a time ({@code m6.session.malformed}). One already recorded is not recorded again; a second
 * close with other amounts keeps the first and writes the ALERT
 * {@code TILL_SESSION_CLOSE_REPLAY_DIFFERS} (wave 2, M6-03). Audit
 * {@code TILL_SESSION_OPENED} or {@code TILL_SESSION_CLOSED}; event
 * {@code till_session.recorded.v1}. The variance threshold and its REVIEW are deferred (README).
 */
@Service
@CommandHandler(permission = "pos.receipt.view")
class RecordSessionHandler implements Handles<RecordSession, UUID> {

    static final String AUDIT_OPENED = "TILL_SESSION_OPENED";
    static final String AUDIT_CLOSED = "TILL_SESSION_CLOSED";
    static final String AUDIT_CLOSE_REPLAY_DIFFERS = "TILL_SESSION_CLOSE_REPLAY_DIFFERS";
    static final String MESSAGE_CLOSE_REPLAY_DIFFERS = "m6.session.close_replay_differs";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordSessionHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordSession command, ScopeContext scope) {
        IngestGuards.requireDeviceScope(scope);
        if (command.sessionId() == null || command.at() == null) {
            throw new ProblemException("m6.session.malformed");
        }
        String table = command.closing() ? "pos.till_session_close" : "pos.till_session";
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from " + table + " where session_id = ?)",
                Boolean.class,
                command.sessionId()))) {
            if (command.closing()) {
                closedAgain(command, scope);
            }
            return command.sessionId();
        }

        if (command.closing()) {
            jdbc.update(
                    """
                    insert into pos.till_session_close (session_id, owner_entity_id, location_id, device_id, closed_by,
                        closed_at, counted_cash, expected_cash, variance)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    command.sessionId(),
                    scope.entityId(),
                    scope.locationId(),
                    scope.deviceId(),
                    command.operatorUserId(),
                    Timestamp.from(command.at()),
                    command.countedCash(),
                    command.expectedCash(),
                    command.variance());
        } else {
            jdbc.update(
                    """
                    insert into pos.till_session (session_id, owner_entity_id, location_id, till_position_id, device_id,
                        operator_user_id, business_date, opened_at, float_amount)
                    values (?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, 0))
                    """,
                    command.sessionId(),
                    scope.entityId(),
                    scope.locationId(),
                    command.tillPositionId(),
                    scope.deviceId(),
                    command.operatorUserId(),
                    command.businessDate(),
                    Timestamp.from(command.at()),
                    command.floatAmount());
        }

        String status = command.closing() ? "CLOSED" : "OPEN";
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("locationId", scope.locationId());
        after.put("variance", command.variance());
        audit.record(
                command.closing() ? AUDIT_CLOSED : AUDIT_OPENED,
                Subject.of("till_session", command.sessionId()),
                null,
                after,
                scope);
        events.publish(new TillSessionRecorded(command.sessionId(), scope.entityId(), scope.locationId(), status));
        return command.sessionId();
    }

    /**
     * A second close of a session already closed. With the same amounts it is a redelivery; with
     * other counted or expected cash or another variance the first close stays and an ALERT names
     * both (wave 2, M6-03, as for a receipt replayed with other content).
     */
    private void closedAgain(RecordSession command, ScopeContext scope) {
        Map<String, Object> first = jdbc.queryForMap(
                "select counted_cash, expected_cash, variance from pos.till_session_close where session_id = ?",
                command.sessionId());
        boolean same = sameAmount((BigDecimal) first.get("counted_cash"), command.countedCash())
                && sameAmount((BigDecimal) first.get("expected_cash"), command.expectedCash())
                && sameAmount((BigDecimal) first.get("variance"), command.variance());
        if (same) {
            return;
        }
        Map<String, Object> differs = new LinkedHashMap<>();
        differs.put("messageId", MESSAGE_CLOSE_REPLAY_DIFFERS);
        differs.put("first", amounts(first.get("counted_cash"), first.get("expected_cash"), first.get("variance")));
        differs.put("later", amounts(command.countedCash(), command.expectedCash(), command.variance()));
        differs.put("deviceId", scope.deviceId());
        audit.record(
                AUDIT_CLOSE_REPLAY_DIFFERS,
                Subject.of("till_session", command.sessionId()),
                null,
                differs,
                scope,
                "The session's close arrived again with other amounts; the first close is kept");
    }

    private static boolean sameAmount(BigDecimal first, BigDecimal later) {
        return first == null ? later == null : later != null && first.compareTo(later) == 0;
    }

    private static Map<String, Object> amounts(Object counted, Object expected, Object variance) {
        Map<String, Object> amounts = new LinkedHashMap<>();
        amounts.put("countedCash", counted);
        amounts.put("expectedCash", expected);
        amounts.put("variance", variance);
        return amounts;
    }
}
