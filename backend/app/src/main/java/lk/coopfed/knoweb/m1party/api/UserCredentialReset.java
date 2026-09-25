package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A user was issued a new credential. {@code resetKind} says which (PASSWORD, SECOND_FACTOR or
 * PIN), never what: no credential material is in an event (AGENTS.md). The two ids are the same
 * user; see {@link UserCreated}.
 */
public record UserCredentialReset(
        UUID appUserId, UUID userId, UUID homeEntityId, String userKind, String status, String resetKind)
        implements DomainEvent {

    public static final String TYPE = "user.credential_reset.v1";
}
