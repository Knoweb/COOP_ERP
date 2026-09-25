package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;

/**
 * Creates a role for the caller's entity, from a template or from scratch (21A section 6,
 * CreateRole; doc 19 section 3.3). The owner is the scope entity, never the request.
 *
 * @param templateRoleId the federation template the role is cloned from, or null for a role
 *                       from scratch; a clone remembers the template's version it was made from
 * @param permissions    the permissions of the new role; null takes the template's set as it is
 *                       (a plain clone); for a role from scratch null means none
 * @param asTemplate     true when the Federation authors a template (owner NULL); only the
 *                       Federation may
 * @param roleClass      OWN, FEDERATION_VIEW or EXTERNAL_TIMEBOXED; null takes the template's
 *                       class, or OWN for a role from scratch. Only the Federation may create a
 *                       class other than OWN
 */
public record CreateRole(
        String nameEn,
        String nameSi,
        String nameTa,
        UUID templateRoleId,
        List<RolePermission> permissions,
        boolean asTemplate,
        String roleClass) {}
