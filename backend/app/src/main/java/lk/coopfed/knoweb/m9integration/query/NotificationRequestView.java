package lk.coopfed.knoweb.m9integration.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model representation of a notification request tracked in the Central Notification Gateway (PGL-M3-02).
 */
public record NotificationRequestView(
        UUID requestId,
        UUID ownerEntityId,
        String channel,
        String recipientTag,
        String messageType,
        String content,
        String status,
        int attempts,
        String provider,
        String providerRef,
        String lastError,
        String dedupKey,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt) {}
