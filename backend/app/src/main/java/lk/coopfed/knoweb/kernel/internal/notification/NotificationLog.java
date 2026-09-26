package lk.coopfed.knoweb.kernel.internal.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The rows of {@code kernel.notification_log} (insert, claim, status update, the retry and
 * de-duplication reads) and of {@code kernel.notification_pending} (what a retry needs and the
 * log must not hold: the recipient and the placeholders, cleared once the notification is
 * settled).
 */
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

    /** A claimed attempt: how many attempts the row now counts and when it was queued. */
    record Claim(int attempts, Instant createdAt) {}

    /** What a retry needs and the log must not hold. */
    record Pending(String recipient, String templateId, String language, Map<String, Object> arguments) {}

    private static final String COLUMNS =
            "notification_id, rule_id, event_id, owner_entity_id, recipient_hash, channel,"
                    + " template_id, language, status, attempts";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    NotificationLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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

    /** The recipient and the placeholders, kept beside the log until the notification is settled. */
    void hold(
            UUID notificationId,
            UUID ownerEntityId,
            String recipient,
            String templateId,
            String language,
            Map<String, Object> arguments,
            Instant now) {
        jdbc.update(
                """
                insert into kernel.notification_pending (
                    notification_id, owner_entity_id, recipient, template_id, language, arguments, created_at
                ) values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                notificationId,
                ownerEntityId,
                recipient,
                templateId,
                language,
                toJson(arguments),
                Timestamp.from(now));
    }

    /** What was held for the notification, or empty once cleared or never held. */
    Optional<Pending> pending(UUID notificationId) {
        return jdbc
                .query(
                        "select recipient, template_id, language, arguments::text as arguments"
                                + " from kernel.notification_pending where notification_id = ? and recipient is not null",
                        (rs, rowNum) -> new Pending(
                                rs.getString("recipient"),
                                rs.getString("template_id"),
                                rs.getString("language"),
                                fromJson(rs.getString("arguments"))),
                        notificationId)
                .stream()
                .findFirst();
    }

    /** The notification is settled: the recipient and the placeholders are not kept a minute longer. */
    void clear(UUID notificationId, Instant now) {
        jdbc.update(
                "update kernel.notification_pending set recipient = null, arguments = '{}'::jsonb, cleared_at = ?"
                        + " where notification_id = ? and cleared_at is null",
                Timestamp.from(now),
                notificationId);
    }

    /**
     * Claims one attempt on a QUEUED row that is due: counts the attempt and moves the next
     * attempt to {@code holdUntil}, so that another sweep (or the same one running late) does
     * not attempt the same row while this one is sending. Empty when the row is not due, not
     * QUEUED, or already claimed.
     */
    Optional<Claim> claim(UUID notificationId, Instant now, Instant holdUntil) {
        return jdbc
                .query(
                        """
                        update kernel.notification_log
                           set attempts = attempts + 1, last_attempt_at = ?, next_attempt_at = ?
                         where notification_id = ? and status = 'QUEUED' and next_attempt_at <= ?
                        returning attempts, created_at
                        """,
                        (rs, rowNum) -> new Claim(
                                rs.getInt("attempts"),
                                rs.getTimestamp("created_at").toInstant()),
                        Timestamp.from(now),
                        Timestamp.from(holdUntil),
                        notificationId,
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    void suppressed(UUID notificationId, String reason) {
        jdbc.update(
                "update kernel.notification_log set status = 'SUPPRESSED', suppressed_reason = ?, next_attempt_at = null"
                        + " where notification_id = ? and status = 'QUEUED'",
                reason,
                notificationId);
    }

    void sent(UUID notificationId, String providerRef, int renderedLength, String language) {
        jdbc.update(
                """
                update kernel.notification_log
                   set status = 'SENT', provider_ref = ?, rendered_length = ?, language = ?,
                       next_attempt_at = null, last_error = null
                 where notification_id = ? and status = 'QUEUED'
                """,
                providerRef,
                renderedLength,
                language,
                notificationId);
    }

    /** One failed attempt (already counted by the claim): the next time, or FAILED when the attempts are spent. */
    void failedAttempt(UUID notificationId, String error, Instant nextAttempt, boolean finalFailure) {
        jdbc.update(
                """
                update kernel.notification_log
                   set status = ?, last_error = ?, next_attempt_at = ?
                 where notification_id = ? and status = 'QUEUED'
                """,
                finalFailure ? "FAILED" : "QUEUED",
                error.length() > 1000 ? error.substring(0, 1000) : error,
                finalFailure ? null : Timestamp.from(nextAttempt),
                notificationId);
    }

    /**
     * Whether the same thing (the dedup key: an event id, a document id) went to the same
     * recipient with the same template inside the hour, from another row (another rule, or a
     * direct send with the same key).
     */
    boolean sentWithinLastHour(String recipientHash, String templateId, UUID dedupKey, UUID exceptNotificationId) {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from kernel.notification_log
                 where recipient_hash = ? and template_id is not distinct from ? and event_id = ?
                   and notification_id <> ? and status = 'SENT'
                   and last_attempt_at > now() - interval '1 hour'
                """,
                Integer.class,
                recipientHash,
                templateId,
                dedupKey,
                exceptNotificationId);
        return count != null && count > 0;
    }

    /** The QUEUED rows whose next attempt is due, the earliest first, at most {@code limit}, under the caller's scope. */
    List<Row> due(Instant now, int limit) {
        return jdbc.query(
                "select " + COLUMNS + " from kernel.notification_log"
                        + " where status = 'QUEUED' and next_attempt_at <= ? order by next_attempt_at limit ?",
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
                Timestamp.from(now),
                limit);
    }

    private String toJson(Map<String, Object> arguments) {
        try {
            return json.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The placeholders of a notification must be JSON values", e);
        }
    }

    private Map<String, Object> fromJson(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The stored placeholders of a notification are not JSON", e);
        }
    }
}
