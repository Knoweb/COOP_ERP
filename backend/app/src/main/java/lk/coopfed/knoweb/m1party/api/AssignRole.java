package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Gives a user a role at the caller's entity, entity-wide or at one of its locations (21A
 * section 6, AssignRole; doc 21 flow 6.3). The entity is the scope entity, never the request.
 *
 * @param scopeLocationId the location the role applies at, or null for the whole entity
 */
public record AssignRole(UUID userId, UUID roleId, UUID scopeLocationId) {}
