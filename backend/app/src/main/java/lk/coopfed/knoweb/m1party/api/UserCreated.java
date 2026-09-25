package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A user was created (doc 21 section 5.3: user id, home entity, kind; no credential material).
 *
 * <p>The user events carry the user's id twice, on purpose. {@code appUserId} is the aggregate
 * id the outbox keys the event on: the outbox skips a component named {@code userId}, because
 * elsewhere that name is the actor. {@code userId} is the name the consumers read (the kernel's
 * permission and scope caches empty a user's entries on {@code user.deactivated.v1} by it).
 */
public record UserCreated(UUID appUserId, UUID userId, UUID homeEntityId, String userKind, String status)
        implements DomainEvent {

    public static final String TYPE = "user.created.v1";
}
