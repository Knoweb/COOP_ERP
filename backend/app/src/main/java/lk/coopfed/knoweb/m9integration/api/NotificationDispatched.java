package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * notification.dispatched.v1: an outgoing notification dispatch fact recorded by the centralized gateway.
 * Zero PII is carried on this event.
 */
public record NotificationDispatched(
        UUID requestId, UUID ownerEntityId, String channel, String messageType, String status, Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "notification.dispatched.v1";
}
