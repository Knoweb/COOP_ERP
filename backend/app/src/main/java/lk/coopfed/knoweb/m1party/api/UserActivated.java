package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A PENDING or LOCKED user became ACTIVE because a credential was issued (doc 21 section 4.4).
 * The two ids are the same user; see {@link UserCreated}.
 */
public record UserActivated(UUID appUserId, UUID userId, UUID homeEntityId, String userKind, String status)
        implements DomainEvent {

    public static final String TYPE = "user.activated.v1";
}
