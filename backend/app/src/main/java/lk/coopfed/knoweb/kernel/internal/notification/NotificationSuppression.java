package lk.coopfed.knoweb.kernel.internal.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether a notification is sent now, never, or later (doc 19 section 7; CR-19A-12). The
 * entity's kill switch for a channel and the same thing to the same person inside the hour
 * suppress: the row is logged SUPPRESSED with the reason, the fact is kept and the message is
 * not. Quiet hours <b>defer</b>: the row stays QUEUED and is due when the window ends, in the
 * business time zone (doc 29 section 6.4, "deferred to 07:00"); a bounced cheque at 21:00
 * reaches the society in the morning, not never.
 *
 * <p>The kill switch is the sending entity's (the event owner's). The window is the
 * <b>recipient's entity's</b> when the audience names it (ROLE_AT_COUNTERPARTY names the
 * counterparty), else the owner's: a distributor does not decide when a society's phones ring.
 * The configuration register answers an entity's own values in that entity's scope only, so
 * another entity's window is read in a short transaction of its own, in its OWN scope.
 */
@Component
class NotificationSuppression {

    private static final Logger log = LoggerFactory.getLogger(NotificationSuppression.class);

    /** What to do with a notification now. */
    sealed interface Decision permits Send, Suppress, Defer {}

    /** Send it. */
    record Send() implements Decision {}

    /** Never send it; the reason is logged on the row. */
    record Suppress(String reason) implements Decision {}

    /** Send it at {@code until}, the end of the quiet hours. */
    record Defer(Instant until) implements Decision {}

    private final ConfigRegistry config;
    private final NotificationLog logRows;
    private final NotificationTransactions transactions;
    private final Clock clock;
    private final ZoneId zone;

    NotificationSuppression(
            ConfigRegistry config,
            NotificationLog logRows,
            NotificationTransactions transactions,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.config = config;
        this.logRows = logRows;
        this.transactions = transactions;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /**
     * Checked when the notification is queued and again before every attempt (19A section 10),
     * so a kill switch thrown or quiet hours begun between the first attempt and a retry still
     * hold.
     *
     * @param dedupKey          what is notified (an event id, a document id): the identity the
     *                          hourly de-duplication compares, with the recipient and the template
     * @param notificationId    this notification's own row, which is never its own duplicate
     * @param owner             the event owner's scope, the one the caller runs in
     * @param recipientEntityId the recipient's entity when the audience named it, else null
     */
    Decision decide(
            String channel,
            String recipientHash,
            String templateId,
            UUID dedupKey,
            UUID notificationId,
            ScopeContext owner,
            UUID recipientEntityId) {
        String key = channel.toLowerCase(Locale.ROOT);

        if (!config.getBoolean("notification." + key + ".enabled", owner, true)) {
            return new Suppress("KILL_SWITCH");
        }

        if (logRows.sentWithinLastHour(recipientHash, templateId, dedupKey, notificationId)) {
            return new Suppress("DUPLICATE_WITHIN_HOUR");
        }

        Optional<Instant> until = quietUntil(windowOf(key, owner, recipientEntityId), clock.instant(), zone);
        if (until.isPresent()) {
            return new Defer(until.get());
        }
        return new Send();
    }

    /** The quiet-hours setting of the recipient's entity, or of the owner when that is not known. */
    private String windowOf(String channelKey, ScopeContext owner, UUID recipientEntityId) {
        String item = "notification." + channelKey + ".quiet_hours";
        if (recipientEntityId == null || recipientEntityId.equals(owner.entityId())) {
            return config.getOrDefault(item, owner, "");
        }
        ScopeContext recipient = SystemScope.own(recipientEntityId, null);
        return transactions.inOwnScope(recipient, () -> config.getOrDefault(item, recipient, ""));
    }

    /**
     * The end of the quiet hours when {@code now} is inside them, in the business zone: today's
     * end, or tomorrow's for a window that wraps midnight (22:00-07:00 at 23:00 ends at 07:00
     * tomorrow). Empty outside the window, for no window, and for a value that cannot be read: a
     * WARN, never an exception thrown past the dispatcher. A window whose two ends are equal is
     * none (it would otherwise be a whole day, deferred for ever).
     */
    static Optional<Instant> quietUntil(String window, Instant now, ZoneId zone) {
        if (window == null || window.isBlank()) {
            return Optional.empty();
        }
        LocalTime from;
        LocalTime to;
        try {
            String[] parts = window.strip().split("-");
            if (parts.length != 2) {
                throw new DateTimeParseException("not HH:mm-HH:mm", window, 0);
            }
            from = LocalTime.parse(parts[0].strip());
            to = LocalTime.parse(parts[1].strip());
        } catch (DateTimeParseException e) {
            log.warn("Quiet hours \"{}\" cannot be read as HH:mm-HH:mm: treated as no quiet hours", window);
            return Optional.empty();
        }
        if (from.equals(to)) {
            log.warn("Quiet hours \"{}\" start and end at the same time: treated as no quiet hours", window);
            return Optional.empty();
        }
        ZonedDateTime local = now.atZone(zone);
        LocalTime time = local.toLocalTime();
        // 22:00-07:00 wraps midnight; 12:00-14:00 does not.
        boolean inside = from.isBefore(to)
                ? !time.isBefore(from) && time.isBefore(to)
                : !time.isBefore(from) || time.isBefore(to);
        if (!inside) {
            return Optional.empty();
        }
        LocalDate day =
                time.isBefore(to) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        return Optional.of(ZonedDateTime.of(day, to, zone).toInstant());
    }
}
