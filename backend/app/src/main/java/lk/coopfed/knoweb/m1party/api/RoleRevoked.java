package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A role assignment was taken away (21A section 6). As for {@link RoleAssigned}, the
 * {@code userId} tells the kernel's permission cache whose entries to empty, so the user loses
 * the permission on the next command and not ten minutes later.
 *
 * @param scopeLocationId null for an entity-wide assignment
 */
public record RoleRevoked(UUID roleId, UUID userId, UUID scopeEntityId, UUID scopeLocationId) implements DomainEvent {

    public static final String TYPE = "role.revoked.v1";
}
