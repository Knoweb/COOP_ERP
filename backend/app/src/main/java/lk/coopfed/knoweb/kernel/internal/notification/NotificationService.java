package lk.coopfed.knoweb.kernel.internal.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import lk.coopfed.knoweb.kernel.api.Notifications;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Delivery (doc 19 section 7; 19A section 10): log the notification QUEUED, apply the
 * suppressions, and after the caller's transaction commits render in the recipient's
 * language, hand to the channel, record SENT or a failed attempt. Three attempts with backoff
 * (one, five, fifteen minutes) inside 24 hours, then FAILED with an ALERT; the retry sweep
 * ({@link RetrySweepJob}) picks up what is due.
 *
 * <p>The queueing runs inside the caller's transaction and scope, so a rolled-back handler
 * leaves no row. The send does not: a provider is called after the commit, from the
 * after-commit callback, and every attempt is claimed and recorded in short transactions of
 * its own ({@link NotificationTransactions}), so a slow or failing provider never rolls back
 * a handler or the recipients sent before it, and two sweeps never attempt the same row. What
 * a retry needs (the recipient, the placeholders) is held in {@code notification_pending}
 * until the row is settled, so any instance can retry; the log itself keeps the hash alone
 * (ADR-27).
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
    private final NotificationTransactions transactions;
    private final Map<String, NotificationChannel> channels = new HashMap<>();
    private final AuditFacade audit;
    private final Clock clock;

    NotificationService(
            NotificationLog logRows,
            NotificationRenderer renderer,
            NotificationSuppression suppression,
            NotificationTransactions transactions,
            List<NotificationChannel> channelBeans,
            AuditFacade audit,
            Clock clock) {
        this.logRows = logRows;
        this.renderer = renderer;
        this.suppression = suppression;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
        for (NotificationChannel channel : channelBeans) {
            channels.put(channel.channel().toUpperCase(), channel);
        }
    }

    @Override
    public UUID send(
            String channel,
            String recipient,
            String language,
            String templateId,
            Map<String, Object> arguments,
            UUID dedupKey,
            ScopeContext ctx) {
        return deliver(
                DIRECT_RULE,
                dedupKey == null ? Ids.next() : dedupKey,
                channel,
                recipient,
                language,
                templateId,
                arguments,
                ctx);
    }

    /**
     * One notification, from a rule or a handler: queue (once per rule, event and recipient),
     * suppress, or hold what the send needs and send once the caller's transaction commits.
     * Returns the notification id, or the id of the earlier row when this is a repeat.
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

        Optional<String> suppress =
                suppression.reasonToSuppress(channelCode, hash, templateId, eventId, notificationId, ctx);
        if (suppress.isPresent()) {
            logRows.suppressed(notificationId, suppress.get());
            return notificationId;
        }

        logRows.hold(notificationId, ctx.entityId(), recipient, templateId, language, arguments, now);

        // The provider is called once the row is committed, never inside the handler's transaction.
        UUID ownerEntityId = ctx.entityId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    attempt(notificationId, channelCode, hash, templateId, eventId, ownerEntityId);
                } catch (RuntimeException e) {
                    // The row is QUEUED and due: the sweep takes it from here.
                    log.error("First attempt of notification {} failed outside the transaction", notificationId, e);
                }
            }
        });
        return notificationId;
    }

    /**
     * One attempt on a QUEUED notification, from the after-commit callback or the sweep. Runs
     * outside any transaction: the claim and the suppression check in one short transaction,
     * the provider call in none, the outcome in another. A row another sweep already claimed
     * is left alone.
     *
     * @return true when the row was attempted (sent, failed or suppressed), false when it was not due
     */
    boolean attempt(
            UUID notificationId,
            String channelCode,
            String recipientHash,
            String templateId,
            UUID eventId,
            UUID ownerEntityId) {
        ScopeContext owner = SystemScope.own(ownerEntityId, null);
        Instant now = clock.instant();

        record Claimed(
                NotificationLog.Claim claim, Optional<NotificationLog.Pending> pending, Optional<String> suppress) {}

        Optional<Claimed> claimed = transactions.inOwnScope(owner, () -> {
            // The hold is the longest backoff: a crash in the middle of the send leaves the row
            // to the sweep after that time, and no second sweep takes it before. A failure
            // recorded below replaces it with the backoff of this attempt.
            Optional<NotificationLog.Claim> claim =
                    logRows.claim(notificationId, now, now.plus(BACKOFF.get(BACKOFF.size() - 1)));
            if (claim.isEmpty()) {
                return Optional.<Claimed>empty();
            }
            Optional<NotificationLog.Pending> pending = logRows.pending(notificationId);
            if (pending.isEmpty()) {
                return Optional.of(new Claimed(claim.get(), pending, Optional.empty()));
            }
            // 19A section 10: the kill switch, quiet hours and the hourly de-duplication are
            // checked before every send, a retry included.
            Optional<String> suppress = suppression.reasonToSuppress(
                    channelCode, recipientHash, templateId, eventId, notificationId, owner);
            if (suppress.isPresent()) {
                logRows.suppressed(notificationId, suppress.get());
                logRows.clear(notificationId, now);
            }
            return Optional.of(new Claimed(claim.get(), pending, suppress));
        });

        if (claimed.isEmpty()) {
            return false;
        }
        NotificationLog.Claim claim = claimed.get().claim();
        if (claimed.get().suppress().isPresent()) {
            return true;
        }
        if (claimed.get().pending().isEmpty()) {
            // Nothing held: the row was written before this table existed, or cleared early.
            transactions.inOwnScope(owner, () -> {
                logRows.failedAttempt(notificationId, "nothing held for the retry", now, true);
                recordFailure(notificationId, owner, "nothing held for the retry");
                return null;
            });
            return true;
        }

        NotificationLog.Pending what = claimed.get().pending().get();
        NotificationChannel channel = channels.get(channelCode);
        NotificationRenderer.Rendered rendered;
        String providerRef;
        try {
            if (channel == null) {
                throw new ProblemException("notification.channel_unknown", Map.of("channel", channelCode));
            }
            rendered = renderer.render(what.templateId(), what.language(), what.arguments());
            providerRef = channel.send(new NotificationChannel.Outgoing(
                    notificationId, what.recipient(), rendered.subject(), rendered.body(), rendered.language()));
        } catch (RuntimeException failure) {
            boolean spent = claim.attempts() >= MAX_ATTEMPTS
                    || claim.createdAt().plus(MAX_AGE).isBefore(now);
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            transactions.inOwnScope(owner, () -> {
                logRows.failedAttempt(notificationId, error, now.plus(backoffAfter(claim.attempts())), spent);
                if (spent) {
                    logRows.clear(notificationId, now);
                    recordFailure(notificationId, owner, String.valueOf(failure.getMessage()));
                }
                return null;
            });
            return true;
        }

        NotificationRenderer.Rendered sent = rendered;
        transactions.inOwnScope(owner, () -> {
            logRows.sent(notificationId, providerRef, sent.body().length(), sent.language());
            logRows.clear(notificationId, now);
            return null;
        });
        return true;
    }

    /** The wait after the n-th failed attempt: one, five, then fifteen minutes. */
    static Duration backoffAfter(int attempts) {
        return BACKOFF.get(Math.min(Math.max(attempts, 1), BACKOFF.size()) - 1);
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

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notifications are sent inside the handler's transaction");
        }
    }
}
