package lk.coopfed.knoweb.kernel.internal.notification;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The reasons a notification is not sent (doc 19 section 7: quiet hours, de-duplication
 * inside an hour, the entity's kill switch for a channel), read from the configuration
 * register in the entity's scope. A suppressed notification is logged SUPPRESSED with the
 * reason: the fact is kept, the message is not.
 */
@Component
class NotificationSuppression {

    private final ConfigRegistry config;
    private final NotificationLog log;
    private final Clock clock;
    private final ZoneId zone;

    NotificationSuppression(
            ConfigRegistry config,
            NotificationLog log,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.config = config;
        this.log = log;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /**
     * The reason to suppress, or empty to send. Checked when the notification is queued and
     * again before every attempt (19A section 10), so a kill switch thrown or quiet hours
     * begun between the first attempt and a retry still hold.
     *
     * @param dedupKey        what is notified (an event id, a document id): the identity the
     *                        hourly de-duplication compares, with the recipient and the template
     * @param notificationId  this notification's own row, which is never its own duplicate
     */
    Optional<String> reasonToSuppress(
            String channel,
            String recipientHash,
            String templateId,
            UUID dedupKey,
            UUID notificationId,
            ScopeContext ctx) {
        String key = channel.toLowerCase(Locale.ROOT);

        if (!config.getBoolean("notification." + key + ".enabled", ctx, true)) {
            return Optional.of("KILL_SWITCH");
        }

        if (inQuietHours(key, ctx)) {
            return Optional.of("QUIET_HOURS");
        }

        if (log.sentWithinLastHour(recipientHash, templateId, dedupKey, notificationId)) {
            return Optional.of("DUPLICATE_WITHIN_HOUR");
        }

        return Optional.empty();
    }

    boolean inQuietHours(String channelKey, ScopeContext ctx) {
        String window = config.getOrDefault("notification." + channelKey + ".quiet_hours", ctx, "");
        if (window == null || window.isBlank()) {
            return false;
        }
        String[] parts = window.split("-");
        LocalTime from = LocalTime.parse(parts[0]);
        LocalTime to = LocalTime.parse(parts[1]);
        LocalTime now = LocalTime.ofInstant(clock.instant(), zone);
        // 22:00-07:00 wraps midnight; 12:00-14:00 does not.
        return from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
    }
}
