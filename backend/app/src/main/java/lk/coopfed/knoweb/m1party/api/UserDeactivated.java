package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A user was deactivated, for good. The kernel empties the user's cached permissions and scopes
 * on it (by {@code userId}); the sync gateway drops the user from the operator snapshot. The two
 * ids are the same user; see {@link UserCreated}.
 */
public record UserDeactivated(UUID appUserId, UUID userId, UUID homeEntityId, String userKind, String status)
        implements DomainEvent {

    public static final String TYPE = "user.deactivated.v1";
}
