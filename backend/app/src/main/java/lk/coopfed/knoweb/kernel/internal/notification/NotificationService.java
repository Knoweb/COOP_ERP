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
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
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
    private final Map<String, NotificationChannel> secondaries = new HashMap<>();
    private final AuditFacade audit;
    private final Clock clock;
    private final RecipientHash recipientHash;

    NotificationService(
            NotificationLog logRows,
            NotificationRenderer renderer,
            NotificationSuppression suppression,
            NotificationTransactions transactions,
            List<NotificationChannel> channelBeans,
            AuditFacade audit,
            Clock clock,
            RecipientHash recipientHash) {
        this.recipientHash = recipientHash;
        this.logRows = logRows;
        this.renderer = renderer;
        this.suppression = suppression;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
        for (NotificationChannel channel : channelBeans) {
            Map<String, NotificationChannel> byRole =
                    channel.role() == NotificationChannel.Role.SECONDARY ? secondaries : channels;
            NotificationChannel earlier = byRole.put(channel.channel().toUpperCase(), channel);
            if (earlier != null) {
                throw new IllegalStateException("Two " + channel.role() + " adapters for the channel "
                        + channel.channel() + ": " + earlier.getClass().getName() + " and "
                        + channel.getClass().getName());
            }
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
                new NotificationAudience.Recipient(channel, recipient, language),
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
     * suppress, or hold what the send needs and send once the caller's transaction commits.
     * Returns the notification id, or the id of the earlier row when this is a repeat.
     */
    UUID deliver(
            UUID ruleId,
            UUID eventId,
            NotificationAudience.Recipient to,
            String templateId,
            Map<String, Object> arguments,
            ScopeContext ctx) {
        requireTransaction();

        String channelCode = to.channel() == null ? "" : to.channel().toUpperCase();
        if (!channels.containsKey(channelCode)) {
            throw new ProblemException("notification.channel_unknown", Map.of("channel", channelCode));
        }
        String recipient = to.recipient();
        if (recipient == null || recipient.isBlank()) {
            throw new ProblemException("notification.recipient_required");
        }
        if (ctx == null || ctx.entityId() == null) {
            throw new ProblemException("scope.required");
        }
        String language = to.language();

        Instant now = clock.instant();
        UUID notificationId = Ids.next();
        String hash = recipientHash.of(channelCode, recipient);
        UUID recipientEntityId = to.entityId();

        if (!logRows.queue(
                notificationId,
                ruleId,
                eventId,
                ctx.entityId(),
                new NotificationLog.Recipient(hash, recipientHash.keyId(), recipientEntityId, to.roleCode()),
                channelCode,
                templateId,
                language,
                now)) {
            log.debug("Notification for rule {} event {} already logged for this recipient", ruleId, eventId);
            return notificationId;
        }

        NotificationSuppression.Decision decision =
                suppression.decide(channelCode, hash, templateId, eventId, notificationId, ctx, recipientEntityId);
        if (decision instanceof NotificationSuppression.Suppress suppress) {
            logRows.suppressed(notificationId, suppress.reason());
            return notificationId;
        }

        logRows.hold(notificationId, ctx.entityId(), recipient, templateId, language, arguments, now);

        if (decision instanceof NotificationSuppression.Defer defer) {
            // Quiet hours (CR-19A-12): held and QUEUED, due when the window ends; the sweep sends
            // it then. No attempt now, so none is registered after the commit.
            logRows.defer(notificationId, defer.until());
            return notificationId;
        }

        // The provider is called once the row is committed, never inside the handler's transaction.
        UUID ownerEntityId = ctx.entityId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    attempt(notificationId, channelCode, hash, templateId, eventId, ownerEntityId, recipientEntityId);
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
     * @param recipientEntityId the recipient's entity, whose quiet hours hold; null for the owner's
     * @return true when the row was attempted (sent, failed, suppressed or deferred), false when it was not due
     */
    boolean attempt(
            UUID notificationId,
            String channelCode,
            String recipientHash,
            String templateId,
            UUID eventId,
            UUID ownerEntityId,
            UUID recipientEntityId) {
        ScopeContext owner = SystemScope.own(ownerEntityId, null);
        Instant now = clock.instant();

        record Claimed(
                NotificationLog.Claim claim,
                Optional<NotificationLog.Pending> pending,
                NotificationSuppression.Decision decision) {}

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
                return Optional.of(new Claimed(claim.get(), pending, new NotificationSuppression.Send()));
            }
            // 19A section 10: the kill switch, quiet hours and the hourly de-duplication are
            // checked before every send, a retry included.
            NotificationSuppression.Decision decision = suppression.decide(
                    channelCode, recipientHash, templateId, eventId, notificationId, owner, recipientEntityId);
            if (decision instanceof NotificationSuppression.Suppress suppress) {
                logRows.suppressed(notificationId, suppress.reason());
                logRows.clear(notificationId, now);
            } else if (decision instanceof NotificationSuppression.Defer defer) {
                // CR-19A-12: quiet hours began since the last attempt. The row waits for the end
                // of the window with the attempt the claim counted given back, and the hold kept.
                // MAX_AGE needs no exemption: a window is at most 15 hours (the register refuses
                // longer), the three backoffs add 21 minutes, and 24 hours are counted from
                // created_at only when an attempt fails.
                logRows.defer(notificationId, defer.until());
            }
            return Optional.of(new Claimed(claim.get(), pending, decision));
        });

        if (claimed.isEmpty()) {
            return false;
        }
        NotificationLog.Claim claim = claimed.get().claim();
        if (!(claimed.get().decision() instanceof NotificationSuppression.Send)) {
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
        // 19A section 10: the primary provider for the first three attempts, then one more
        // through the channel's secondary provider when there is one.
        NotificationChannel secondary = secondaries.get(channelCode);
        NotificationChannel channel =
                secondary != null && claim.attempts() > MAX_ATTEMPTS ? secondary : channels.get(channelCode);
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
            boolean spent = claim.attempts() >= maxAttempts(channelCode)
                    || claim.createdAt().plus(MAX_AGE).isBefore(now);
            // The provider's own text can name the recipient or quote the body: it goes to the
            // application log at DEBUG with the id, and the row and the insert-only audit get the
            // class and a category only (TWK-21, M9-10; AGENTS.md: no phone number in an audit row).
            log.debug("Notification {} attempt {} failed", notificationId, claim.attempts(), failure);
            String error = errorOf(failure);
            transactions.inOwnScope(owner, () -> {
                logRows.failedAttempt(notificationId, error, now.plus(backoffAfter(claim.attempts())), spent);
                if (spent) {
                    logRows.clear(notificationId, now);
                    recordFailure(notificationId, owner, error);
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

    /**
     * What the log and the audit keep of a failure: the exception's class and a category the
     * adapter chose ({@link NotificationChannel.SendFailed}), or a problem's message id; never
     * the exception's message.
     */
    static String errorOf(RuntimeException failure) {
        String kind = failure.getClass().getSimpleName();
        if (failure instanceof NotificationChannel.SendFailed sendFailed) {
            return kind + ": " + sendFailed.category();
        }
        if (failure instanceof ProblemException problem) {
            return kind + ": " + problem.messageId();
        }
        return kind + ": " + NotificationChannel.FailureCategory.UNKNOWN;
    }

    /** Three attempts, and a fourth through the secondary provider when the channel has one. */
    int maxAttempts(String channelCode) {
        return secondaries.containsKey(channelCode) ? MAX_ATTEMPTS + 1 : MAX_ATTEMPTS;
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
