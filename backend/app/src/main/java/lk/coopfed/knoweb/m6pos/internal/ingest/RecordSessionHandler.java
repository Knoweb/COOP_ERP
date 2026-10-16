package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
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
 * {@code TILL_SESSION_OPENED} or {@code TILL_SESSION_CLOSED}, and {@code TILL_SESSION_SHORTAGE_REVIEW}
 * (REVIEW) when a close's cash shortage strictly exceeds {@code pos.session_variance_review_threshold}
 * (TILL-SELL-11, C-02c, doc 26 section 7); event {@code till_session.recorded.v1}.
 */
@Service
@CommandHandler(permission = "pos.receipt.view")
class RecordSessionHandler implements Handles<RecordSession, UUID> {

    static final String AUDIT_OPENED = "TILL_SESSION_OPENED";
    static final String AUDIT_CLOSED = "TILL_SESSION_CLOSED";
    static final String AUDIT_CLOSE_REPLAY_DIFFERS = "TILL_SESSION_CLOSE_REPLAY_DIFFERS";
    static final String MESSAGE_CLOSE_REPLAY_DIFFERS = "m6.session.close_replay_differs";
    static final String AUDIT_SHORTAGE_REVIEW = "TILL_SESSION_SHORTAGE_REVIEW";
    static final String MESSAGE_SHORTAGE_REVIEW = "m6.session.shortage_review";
    static final String CONFIG_THRESHOLD = "pos.session_variance_review_threshold";
    static final String DEFAULT_THRESHOLD_STR = "500.00";
    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("500.00");

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ConfigRegistry config;

    RecordSessionHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, ConfigRegistry config) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.config = config;
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

        if (command.closing()) {
            BigDecimal threshold = reviewThreshold(scope);
            BigDecimal variance = resolveVariance(command);
            if (isShortageExceeded(variance, threshold, command)) {
                BigDecimal shortage = variance.negate().setScale(2, RoundingMode.HALF_UP);
                Map<String, Object> review = new LinkedHashMap<>();
                review.put("messageId", MESSAGE_SHORTAGE_REVIEW);
                review.put("sessionId", command.sessionId());
                review.put("countedCash", command.countedCash());
                review.put("expectedCash", command.expectedCash());
                review.put("variance", command.variance());
                review.put("shortage", shortage);
                review.put("threshold", threshold);
                review.put("locationId", scope.locationId());
                review.put("deviceId", scope.deviceId());
                audit.record(
                        AUDIT_SHORTAGE_REVIEW,
                        Subject.of("till_session", command.sessionId()),
                        null,
                        review,
                        scope,
                        "Cash shortage of " + shortage + " exceeds review threshold " + threshold);
            }
        }

        events.publish(new TillSessionRecorded(command.sessionId(), scope.entityId(), scope.locationId(), status));
        return command.sessionId();
    }

    /**
     * Resolves the threshold from ConfigRegistry without masking negative values with abs().
     * If unparseable or negative, falls back safely to DEFAULT_THRESHOLD (500.00).
     */
    private BigDecimal reviewThreshold(ScopeContext scope) {
        String raw = config.getOrDefault(CONFIG_THRESHOLD, scope, DEFAULT_THRESHOLD_STR);
        try {
            BigDecimal val = new BigDecimal(raw);
            if (val.compareTo(BigDecimal.ZERO) < 0) {
                return DEFAULT_THRESHOLD;
            }
            return val.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return DEFAULT_THRESHOLD;
        }
    }

    private static BigDecimal resolveVariance(RecordSession command) {
        if (command.variance() != null) {
            return command.variance();
        }
        if (command.countedCash() != null && command.expectedCash() != null) {
            return command.countedCash().subtract(command.expectedCash());
        }
        return null;
    }

    /**
     * Checks if a shortage strictly exceeds the threshold.
     * Review only when countedCash < expectedCash and shortage strictly exceeds the applicable threshold.
     * Exact threshold, zero variance, and surplus must not trigger a shortage review.
     */
    private static boolean isShortageExceeded(BigDecimal variance, BigDecimal threshold, RecordSession command) {
        if (variance == null || threshold == null) {
            return false;
        }
        if (command.countedCash() != null && command.expectedCash() != null) {
            if (command.countedCash().compareTo(command.expectedCash()) >= 0) {
                return false;
            }
        } else if (variance.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        BigDecimal shortage = variance.negate();
        return shortage.compareTo(threshold) > 0;
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
