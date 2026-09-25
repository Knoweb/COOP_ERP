package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A user's details changed. The two ids are the same user; see {@link UserCreated}. */
public record UserUpdated(UUID appUserId, UUID userId, UUID homeEntityId, String userKind, String status)
        implements DomainEvent {

    public static final String TYPE = "user.updated.v1";
}
