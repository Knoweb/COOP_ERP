package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A role was created, its permissions changed or it was retired (21A section 6; doc 21 section
 * 5.3: "role id, owner, version, permission diff"). It names no user, so the kernel's
 * permission cache empties every entry when it arrives (PermissionCacheInvalidator): any holder
 * of the role may have gained or lost a permission.
 *
 * @param ownerEntityId      the owning entity; null for a federation template
 * @param change             CREATED, AMENDED or RETIRED
 * @param permissionsAdded   codes the role holds now and did not before, sorted
 * @param permissionsRemoved codes the role held before and does not now, sorted
 */
public record RoleChanged(
        UUID roleId,
        UUID ownerEntityId,
        int version,
        String status,
        boolean template,
        String change,
        List<String> permissionsAdded,
        List<String> permissionsRemoved)
        implements DomainEvent {

    public static final String TYPE = "role.changed.v1";

    public static final String CREATED = "CREATED";
    public static final String AMENDED = "AMENDED";
    public static final String RETIRED = "RETIRED";
}
