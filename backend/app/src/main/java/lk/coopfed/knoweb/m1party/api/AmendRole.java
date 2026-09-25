package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;

/**
 * Replaces the permission set of a role and raises its version (21A sections 6 and 6.1).
 *
 * @param permissions          the whole new set; what is not in it is taken away
 * @param adoptTemplateVersion true when the administrator has read the template diff and takes
 *                             the template's current version as seen (doc 19 section 3.3: "may
 *                             re-sync with a diff shown"); the role's permissions are still the
 *                             ones given here, nothing is copied behind the caller's back
 */
public record AmendRole(UUID roleId, List<RolePermission> permissions, boolean adoptTemplateVersion) {}
