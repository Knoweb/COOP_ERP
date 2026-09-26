package lk.coopfed.knoweb.kernel.internal.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import lk.coopfed.knoweb.kernel.api.Notifications;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Delivery (doc 19 section 7; 19A section 10): log the notification QUEUED, apply the
 * suppressions, render in the recipient's language, hand to the channel, record SENT or a
 * failed attempt. Three attempts with backoff (one, five, fifteen minutes) inside 24 hours,
 * then FAILED with an ALERT; the retry sweep ({@link RetrySweepJob}) picks up what is due.
 *
 * <p>Runs inside the caller's transaction and scope. The recipient itself lives only in the
 * pending map of this instance for the retries; a row the sweep picks up after a restart
 * has the hash alone and is FAILED with a clear error, which is the honest outcome when
 * nothing may store the number (ADR-27). A rule-driven send is re-resolved by the dispatcher
 * on replay instead.
 */
@Component
class NotificationService implements Notifications {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    static final String AUDIT_FAILED = "NOTIFICATION_FAILED";
    static final int MAX_ATTEMPTS = 3;
    static final Duration MAX_AGE = Duration.ofHours(24);
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    /** The direct sends have no rule; this id stands for "sent by a handler". */
    static final UUID DIRECT_RULE = UUID.fromString("00000000-0000-7000-8000-00000000d1ec");

    private final NotificationLog logRows;
    private final NotificationRenderer renderer;
    private final NotificationSuppression suppression;
    private final Map<String, NotificationChannel> channels = new HashMap<>();
    private final AuditFacade audit;
    private final Clock clock;

    /** What a retry needs and the log must not hold: the recipient and the arguments, per notification. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    record Pending(
            String recipient, String templateId, String language, Map<String, Object> arguments, Instant since) {}

    NotificationService(
            NotificationLog logRows,
            NotificationRenderer renderer,
            NotificationSuppression suppression,
            List<NotificationChannel> channelBeans,
            AuditFacade audit,
            Clock clock) {
        this.logRows = logRows;
        this.renderer = renderer;
        this.suppression = suppression;
        this.audit = audit;
        this.clock = clock;
        for (NotificationChannel channel : channelBeans) {
            channels.put(channel.channel().toUpperCase(), channel);
        }
    }

    @Override
    public Delivery send(
            String channel,
            String recipient,
            String language,
            String templateId,
            Map<String, Object> arguments,
            UUID dedupKey,
            ScopeContext ctx) {
        UUID notificationId = deliver(
                DIRECT_RULE,
                dedupKey == null ? Ids.next() : dedupKey,
                channel,
                recipient,
                language,
                templateId,
                arguments,
                ctx);
        // A repeat of the same key and recipient was never logged under this id: nobody was
        // reached by this call, which for the caller is the same as a suppression.
        Outcome outcome = logRows.status(notificationId).map(Outcome::valueOf).orElse(Outcome.SUPPRESSED);
        return new Delivery(notificationId, outcome);
    }

    /**
     * One notification, from a rule or a handler: queue (once per rule, event and recipient),
     * suppress or send. Returns the notification id, or the id of the earlier row when this
     * is a repeat.
     */
    UUID deliver(
            UUID ruleId,
            UUID eventId,
            String channel,
            String recipient,
            String language,
            String templateId,
            Map<String, Object> arguments,
            ScopeContext ctx) {
        requireTransaction();

        String channelCode = channel == null ? "" : channel.toUpperCase();
        if (!channels.containsKey(channelCode)) {
            throw new ProblemException("notification.channel_unknown", Map.of("channel", channelCode));
        }
        if (recipient == null || recipient.isBlank()) {
            throw new ProblemException("notification.recipient_required");
        }
        if (ctx == null || ctx.entityId() == null) {
            throw new ProblemException("scope.required");
        }

        Instant now = clock.instant();
        UUID notificationId = Ids.next();
        String hash = NotificationLog.hash(channelCode, recipient);

        if (!logRows.queue(
                notificationId, ruleId, eventId, ctx.entityId(), hash, channelCode, templateId, language, now)) {
            log.debug("Notification for rule {} event {} already logged for this recipient", ruleId, eventId);
            return notificationId;
        }

        Optional<String> suppress = suppression.reasonToSuppress(channelCode, hash, templateId, ctx);
        if (suppress.isPresent()) {
            logRows.suppressed(notificationId, suppress.get());
            return notificationId;
        }

        pending.put(
                notificationId,
                new Pending(recipient, templateId, language, arguments == null ? Map.of() : arguments, now));
        attempt(notificationId, channelCode, 0, ctx);
        return notificationId;
    }

    /** One attempt on a pending notification; the caller has the scope and the transaction. */
    void attempt(UUID notificationId, String channelCode, int attemptsSoFar, ScopeContext ctx) {
        Pending what = pending.get(notificationId);
        Instant now = clock.instant();

        if (what == null) {
            // The recipient is gone with the instance that queued it: nothing may store it.
            logRows.failedAttempt(
                    notificationId, "recipient not held on this instance after a restart", now, now, true);
            recordFailure(notificationId, ctx, "recipient not held after a restart");
            return;
        }

        NotificationRenderer.Rendered rendered = renderer.render(what.templateId(), what.language(), what.arguments());
        NotificationChannel channel = channels.get(channelCode);

        try {
            String providerRef = channel.send(new NotificationChannel.Outgoing(
                    notificationId, what.recipient(), rendered.subject(), rendered.body(), rendered.language()));
            logRows.sent(notificationId, providerRef, rendered.body().length(), rendered.language(), now);
            pending.remove(notificationId);
        } catch (RuntimeException failure) {
            int attempts = attemptsSoFar + 1;
            boolean spent =
                    attempts >= MAX_ATTEMPTS || what.since().plus(MAX_AGE).isBefore(now);
            Duration backoff = BACKOFF.get(Math.min(attempts, BACKOFF.size()) - 1);
            logRows.failedAttempt(
                    notificationId,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    now,
                    now.plus(backoff),
                    spent);
            if (spent) {
                pending.remove(notificationId);
                recordFailure(notificationId, ctx, String.valueOf(failure.getMessage()));
            }
        }
    }

    private void recordFailure(UUID notificationId, ScopeContext ctx, String error) {
        try {
            audit.record(
                    AUDIT_FAILED,
                    Subject.of("notification", notificationId),
                    null,
                    Map.of("error", error),
                    ctx,
                    "Not delivered after every attempt");
        } catch (RuntimeException e) {
            log.error("Could not record NOTIFICATION_FAILED for {}", notificationId, e);
        }
    }

    boolean holds(UUID notificationId) {
        return pending.containsKey(notificationId);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notifications are sent inside the handler's transaction");
        }
    }
}
