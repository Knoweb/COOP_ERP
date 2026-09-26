package lk.coopfed.knoweb.kernel.internal.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The rows of {@code kernel.notification_log}: insert, status update, the retry and de-duplication reads. */
@Component
class NotificationLog {

    /** A row the sweep or the dispatcher works on. */
    record Row(
            UUID notificationId,
            UUID ruleId,
            UUID eventId,
            UUID ownerEntityId,
            String recipientHash,
            String channel,
            String templateId,
            String language,
            String status,
            int attempts) {}

    private static final String COLUMNS =
            "notification_id, rule_id, event_id, owner_entity_id, recipient_hash, channel,"
                    + " template_id, language, status, attempts";

    private final JdbcTemplate jdbc;

    NotificationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The hash a recipient is stored as: the channel and the recipient, never the recipient itself. */
    static String hash(String channel, String recipient) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest((channel + ":" + recipient.strip()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /**
     * A QUEUED row, or false when the same rule, event and recipient were logged before: the
     * unique key is the idempotency of a replayed event.
     */
    boolean queue(
            UUID notificationId,
            UUID ruleId,
            UUID eventId,
            UUID ownerEntityId,
            String recipientHash,
            String channel,
            String templateId,
            String language,
            Instant now) {
        // ON CONFLICT DO NOTHING, not a caught duplicate-key error: a failed statement aborts the
        // PostgreSQL transaction, and the next recipient of the same event would then fail with it.
        int inserted = jdbc.update(
                """
                    insert into kernel.notification_log (
                        notification_id, rule_id, event_id, owner_entity_id, recipient_hash, channel,
                        template_id, language, status, created_at, next_attempt_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                    on conflict (rule_id, event_id, recipient_hash) do nothing
                    """,
                notificationId,
                ruleId,
                eventId,
                ownerEntityId,
                recipientHash,
                channel,
                templateId,
                language,
                Timestamp.from(now),
                Timestamp.from(now));
        return inserted == 1;
    }

    void suppressed(UUID notificationId, String reason) {
        jdbc.update(
                "update kernel.notification_log set status = 'SUPPRESSED', suppressed_reason = ?, next_attempt_at = null"
                        + " where notification_id = ?",
                reason,
                notificationId);
    }

    void sent(UUID notificationId, String providerRef, int renderedLength, String language, Instant now) {
        jdbc.update(
                """
                update kernel.notification_log
                   set status = 'SENT', provider_ref = ?, rendered_length = ?, language = ?,
                       attempts = attempts + 1, last_attempt_at = ?, next_attempt_at = null, last_error = null
                 where notification_id = ?
                """,
                providerRef,
                renderedLength,
                language,
                Timestamp.from(now),
                notificationId);
    }

    /** One more failed attempt: back to QUEUED with the next time, or FAILED when the attempts are spent. */
    void failedAttempt(UUID notificationId, String error, Instant now, Instant nextAttempt, boolean finalFailure) {
        jdbc.update(
                """
                update kernel.notification_log
                   set status = ?, last_error = ?, attempts = attempts + 1, last_attempt_at = ?, next_attempt_at = ?
                 where notification_id = ?
                """,
                finalFailure ? "FAILED" : "QUEUED",
                error.length() > 1000 ? error.substring(0, 1000) : error,
                Timestamp.from(now),
                finalFailure ? null : Timestamp.from(nextAttempt),
                notificationId);
    }

    /** The status of one row under the caller's scope; empty when there is no such row. */
    Optional<String> status(UUID notificationId) {
        return jdbc
                .queryForList(
                        "select status from kernel.notification_log where notification_id = ?",
                        String.class,
                        notificationId)
                .stream()
                .findFirst();
    }

    boolean sentWithinLastHour(String recipientHash, String templateId) {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from kernel.notification_log
                 where recipient_hash = ? and template_id is not distinct from ? and status = 'SENT'
                   and last_attempt_at > now() - interval '1 hour'
                """,
                Integer.class,
                recipientHash,
                templateId);
        return count != null && count > 0;
    }

    /** The QUEUED rows whose next attempt is due, under the caller's scope. */
    List<Row> due(Instant now) {
        return jdbc.query(
                "select " + COLUMNS + " from kernel.notification_log"
                        + " where status = 'QUEUED' and next_attempt_at <= ? order by next_attempt_at",
                (rs, rowNum) -> new Row(
                        rs.getObject("notification_id", UUID.class),
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("recipient_hash"),
                        rs.getString("channel"),
                        rs.getString("template_id"),
                        rs.getString("language"),
                        rs.getString("status"),
                        rs.getInt("attempts")),
                Timestamp.from(now));
    }
}
