package lk.coopfed.knoweb.m9integration.internal.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.NotificationChannel.FailureCategory;
import lk.coopfed.knoweb.kernel.api.NotificationChannel.SendFailed;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.NotificationDispatched;
import lk.coopfed.knoweb.m9integration.api.NotificationGateway;
import lk.coopfed.knoweb.m9integration.api.NotificationRequest;
import lk.coopfed.knoweb.m9integration.api.NotificationResult;
import lk.coopfed.knoweb.m9integration.query.NotificationRequestView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralized Notification Gateway implementation (Wave 3, PGL-M3-02).
 *
 * <p>Manages lifecycle, provider abstraction, retry mechanics, status tracking, and PII-scrubbed
 * audit trails for SMS and message alert deliveries.
 */
@Service
@CommandHandler(permission = "int.notify.manage")
@Transactional
public class CentralNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(CentralNotificationGateway.class);

    public static final int MAX_ATTEMPTS = 3;
    public static final List<Duration> BACKOFF =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final List<SmsGateway> gateways;
    private final String configuredProvider;

    public CentralNotificationGateway(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            List<SmsGateway> gateways,
            @Value("${coop-erp.integration.sms.provider:log}") String configuredProvider) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.gateways = gateways;
        this.configuredProvider = configuredProvider;
    }

    @Override
    @Transactional
    public NotificationResult dispatch(NotificationRequest request, ScopeContext scope) {
        if (request == null) {
            throw new IllegalArgumentException("NotificationRequest must not be null");
        }
        UUID ownerEntityId =
                request.ownerEntityId() != null ? request.ownerEntityId() : (scope != null ? scope.entityId() : null);
        if (ownerEntityId == null) {
            throw new ProblemException("m9.gateway.owner_entity_required");
        }

        // 1. Check idempotency deduplication key
        if (request.dedupKey() != null && !request.dedupKey().isBlank()) {
            Optional<NotificationRequestView> existing = findByDedupKey(request.dedupKey(), scope);
            if (existing.isPresent()) {
                NotificationRequestView view = existing.get();
                if ("SENT".equalsIgnoreCase(view.status())) {
                    log.info("Deduplicating notification request for dedupKey {}", request.dedupKey());
                    return new NotificationResult(view.requestId(), "SENT", view.providerRef(), null, true);
                }
                if ("PENDING".equalsIgnoreCase(view.status())) {
                    log.info("Notification request {} already PENDING, attempting send", view.requestId());
                    return executeSend(
                            view.requestId(),
                            request.recipient(),
                            request.content(),
                            request.channel(),
                            request.messageType(),
                            view.attempts(),
                            ownerEntityId,
                            scope);
                }
            }
        }

        Instant now = clock.instant();
        UUID requestId = request.requestId();
        String recipientTag = maskRecipient(request.recipient());
        SmsGateway gateway = resolveGateway();

        // 2. Insert new notification request in PENDING state
        jdbc.update(
                """
                insert into integration.notification_request
                    (request_id, owner_entity_id, channel, recipient, recipient_tag, message_type, content,
                     status, attempts, provider, dedup_key, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
                """,
                requestId,
                ownerEntityId,
                request.channel(),
                request.recipient(),
                recipientTag,
                request.messageType(),
                request.content(),
                gateway.name(),
                request.dedupKey(),
                Timestamp.from(now),
                Timestamp.from(now));

        // 3. Attempt initial dispatch
        return executeSend(
                requestId,
                request.recipient(),
                request.content(),
                request.channel(),
                request.messageType(),
                0,
                ownerEntityId,
                scope);
    }

    @Override
    @Transactional
    public NotificationResult retry(UUID requestId, ScopeContext scope) {
        Optional<NotificationRequestView> found = findById(requestId, scope);
        if (found.isEmpty()) {
            throw new ProblemException("m9.gateway.request_not_found");
        }
        NotificationRequestView view = found.get();
        if ("SENT".equalsIgnoreCase(view.status())) {
            return new NotificationResult(view.requestId(), "SENT", view.providerRef(), null, true);
        }

        String recipient = jdbc.queryForObject(
                "select recipient from integration.notification_request where request_id = ?", String.class, requestId);

        return executeSend(
                view.requestId(),
                recipient,
                view.content(),
                view.channel(),
                view.messageType(),
                view.attempts(),
                view.ownerEntityId(),
                scope);
    }

    @Override
    public Optional<NotificationRequestView> findById(UUID requestId, ScopeContext scope) {
        if (requestId == null) {
            return Optional.empty();
        }
        List<NotificationRequestView> results = jdbc.query(
                """
                select request_id, owner_entity_id, channel, recipient_tag, message_type, content,
                       status, attempts, provider, provider_ref, last_error, dedup_key,
                       next_attempt_at, created_at, updated_at
                  from integration.notification_request
                 where request_id = ?
                """,
                this::mapRow,
                requestId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<NotificationRequestView> findByDedupKey(String dedupKey, ScopeContext scope) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return Optional.empty();
        }
        List<NotificationRequestView> results = jdbc.query(
                """
                select request_id, owner_entity_id, channel, recipient_tag, message_type, content,
                       status, attempts, provider, provider_ref, last_error, dedup_key,
                       next_attempt_at, created_at, updated_at
                  from integration.notification_request
                 where dedup_key = ?
                 order by created_at desc
                 limit 1
                """,
                this::mapRow,
                dedupKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private NotificationResult executeSend(
            UUID requestId,
            String recipient,
            String content,
            String channel,
            String messageType,
            int currentAttempts,
            UUID ownerEntityId,
            ScopeContext scope) {
        Instant now = clock.instant();
        int attemptNumber = currentAttempts + 1;
        SmsGateway gateway = resolveGateway();

        try {
            String providerRef = gateway.send(requestId, recipient, content);

            jdbc.update(
                    """
                    update integration.notification_request
                       set status = 'SENT',
                           attempts = ?,
                           provider = ?,
                           provider_ref = ?,
                           last_error = null,
                           next_attempt_at = null,
                           updated_at = ?
                     where request_id = ?
                    """,
                    attemptNumber,
                    gateway.name(),
                    providerRef,
                    Timestamp.from(now),
                    requestId);

            recordAudit(
                    "NOTIFICATION_SENT",
                    requestId,
                    channel,
                    messageType,
                    gateway.name(),
                    null,
                    scope,
                    "Delivered via " + gateway.name());
            publishDispatched(requestId, ownerEntityId, channel, messageType, "SENT");
            return new NotificationResult(requestId, "SENT", providerRef, null, false);
        } catch (RuntimeException failure) {
            String error = errorOf(failure);
            boolean spent = attemptNumber >= MAX_ATTEMPTS;
            String nextStatus = spent ? "FAILED" : "PENDING";
            Instant nextAttemptAt = spent ? null : now.plus(backoffAfter(attemptNumber));

            jdbc.update(
                    """
                    update integration.notification_request
                       set status = ?,
                           attempts = ?,
                           provider = ?,
                           last_error = ?,
                           next_attempt_at = ?,
                           updated_at = ?
                     where request_id = ?
                    """,
                    nextStatus,
                    attemptNumber,
                    gateway.name(),
                    error,
                    nextAttemptAt != null ? Timestamp.from(nextAttemptAt) : null,
                    Timestamp.from(now),
                    requestId);

            if (spent) {
                recordAudit(
                        "NOTIFICATION_FAILED",
                        requestId,
                        channel,
                        messageType,
                        gateway.name(),
                        error,
                        scope,
                        "Delivery permanently failed after " + attemptNumber + " attempts: " + error);
            }
            publishDispatched(requestId, ownerEntityId, channel, messageType, nextStatus);
            return new NotificationResult(requestId, nextStatus, null, error, false);
        }
    }

    private SmsGateway resolveGateway() {
        if (gateways != null && !gateways.isEmpty()) {
            for (SmsGateway g : gateways) {
                if (g.name().equalsIgnoreCase(configuredProvider)) {
                    return g;
                }
            }
            return gateways.get(0);
        }
        return new LogSmsGateway();
    }

    private Duration backoffAfter(int attemptNumber) {
        int idx = Math.min(attemptNumber - 1, BACKOFF.size() - 1);
        return BACKOFF.get(Math.max(0, idx));
    }

    private String errorOf(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        if (t instanceof SendFailed sf) {
            return sf.category() + ": " + scrub(sf.getMessage());
        }
        return scrub(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
    }

    private String scrub(String raw) {
        if (raw == null) {
            return "error";
        }
        String clean = raw.replaceAll("\\b0\\d{9}\\b", "***")
                .replaceAll("\\b\\d{9}[vVxX]?\\b", "***")
                .replaceAll("\\b\\d{12}\\b", "***");
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private NotificationRequestView mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp nextAttempt = rs.getTimestamp("next_attempt_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");

        return new NotificationRequestView(
                rs.getObject("request_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("channel"),
                rs.getString("recipient_tag"),
                rs.getString("message_type"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("provider"),
                rs.getString("provider_ref"),
                rs.getString("last_error"),
                rs.getString("dedup_key"),
                nextAttempt != null ? nextAttempt.toInstant() : null,
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null);
    }

    private String maskRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "***";
        }
        String clean = recipient.trim();
        if (clean.length() <= 4) {
            return "***";
        }
        int keepHead = Math.min(3, clean.length() / 3);
        int keepTail = 2;
        return clean.substring(0, keepHead) + "***" + clean.substring(clean.length() - keepTail);
    }

    private void recordAudit(
            String eventType,
            UUID requestId,
            String channel,
            String messageType,
            String provider,
            String error,
            ScopeContext scope,
            String summary) {
        if (audit == null || scope == null) {
            return;
        }
        try {
            Map<String, Object> after = error == null
                    ? Map.of("channel", channel, "messageType", messageType, "provider", provider)
                    : Map.of("channel", channel, "messageType", messageType, "provider", provider, "error", error);
            audit.record(eventType, Subject.of("notification_request", requestId), null, after, scope, summary);
        } catch (RuntimeException e) {
            log.error("Could not record audit {} for notification request {}", eventType, requestId, e);
        }
    }

    private void publishDispatched(
            UUID requestId, UUID ownerEntityId, String channel, String messageType, String status) {
        if (events == null) {
            return;
        }
        try {
            events.publish(new NotificationDispatched(
                    requestId, ownerEntityId, channel, messageType, status, clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not publish notification.dispatched.v1 for request {}", requestId, e);
        }
    }
}

