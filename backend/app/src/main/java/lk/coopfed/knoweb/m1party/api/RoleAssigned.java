package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A user was given a role at an entity, entity-wide or at one location (21A section 6). The
 * {@code userId} is what the kernel's permission cache reads: that user's entries are emptied
 * and nobody else's (PermissionCacheInvalidator). The outbox files it under the role.
 *
 * @param scopeLocationId null for an entity-wide assignment
 */
public record RoleAssigned(UUID roleId, UUID userId, UUID scopeEntityId, UUID scopeLocationId) implements DomainEvent {

    public static final String TYPE = "role.assigned.v1";
}
