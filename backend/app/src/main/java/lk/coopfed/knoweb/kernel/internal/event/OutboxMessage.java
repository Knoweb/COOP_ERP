package lk.coopfed.knoweb.kernel.internal.event;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String source,
        long sourceSeq,
        UUID ownerEntityId,
        UUID locationId,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        UUID causationId,
        UUID actorUserId,
        String engineVersion,
        String payload) {}
