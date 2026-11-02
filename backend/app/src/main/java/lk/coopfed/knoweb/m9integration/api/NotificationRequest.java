package lk.coopfed.knoweb.m9integration.api;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;

/**
 * A notification dispatch request passed to the Central Notification Gateway (PGL-M3-02).
 *
 * @param requestId     stable unique identifier of the notification request
 * @param ownerEntityId owning society or federation entity
 * @param channel       delivery channel (SMS, EMAIL, MESSAGE)
 * @param recipient     delivery address or phone number (never leaked to logs or audit)
 * @param messageType   classification tag (e.g., LOW_STOCK, PRICE_UPDATE, BIRTHDAY_ALERT)
 * @param content       rendered notification body
 * @param dedupKey      idempotency key preventing duplicate dispatch on event redelivery
 * @param metadata      optional contextual key-value pairs
 */
public record NotificationRequest(
        UUID requestId,
        UUID ownerEntityId,
        String channel,
        String recipient,
        String messageType,
        String content,
        String dedupKey,
        Map<String, Object> metadata) {

    public NotificationRequest {
        if (requestId == null) {
            requestId = Ids.next();
        }
        if (channel == null || channel.isBlank()) {
            channel = "SMS";
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public NotificationRequest(
            UUID ownerEntityId, String channel, String recipient, String messageType, String content, String dedupKey) {
        this(Ids.next(), ownerEntityId, channel, recipient, messageType, content, dedupKey, Map.of());
    }
}
