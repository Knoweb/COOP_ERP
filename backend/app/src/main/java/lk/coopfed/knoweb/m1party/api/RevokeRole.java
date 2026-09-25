package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Takes a role assignment away (21A section 6, RevokeRole): the row is deleted and the audit
 * record keeps what it was.
 *
 * @param scopeLocationId the location of the assignment, or null for the entity-wide one
 * @param reason          why, for the audit record; optional
 */
public record RevokeRole(UUID userId, UUID roleId, UUID scopeLocationId, String reason) {}
