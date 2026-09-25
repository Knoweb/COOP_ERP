package lk.coopfed.knoweb.m1party.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The read side of roles, assignments and separation-of-duties pairs (21A section 7: ListRoles,
 * GetRoleDiff; the duties list of section 8). Every answer is what the caller's row-level
 * security shows: the scope entity's own roles and the federation templates.
 */
public interface SecurityQueries {

    /** The entity's roles and the federation templates, templates last, each with its permissions. */
    List<RoleView> listRoles(ScopeContext scope);

    Optional<RoleView> getRole(UUID roleId, ScopeContext scope);

    /**
     * The difference between a role and the current version of the template it was cloned from
     * (doc 19 DR-4, "notify and offer diff"; assumes doc 10 E-04). Empty when the role is not
     * visible or was not cloned from a template.
     */
    Optional<RoleDiff> getRoleDiff(UUID roleId, ScopeContext scope);

    /** The pairs in force for the scope entity: the federation defaults and the entity's own. */
    List<SodPairView> listSodPairs(ScopeContext scope);

    /** The assignments at the scope entity, of one user or of everybody when {@code userId} is null. */
    List<AssignmentView> listAssignments(UUID userId, ScopeContext scope);

    /** A permission of a role and its limits (null when it carries none). */
    record RolePermissionView(String permissionCode, Map<String, Object> limits) {}

    /**
     * @param templateVersion the current version of the template, when the role is a clone
     * @param templateUpdated true when the template has moved on since the clone last took it
     *                        (the "template updated" marker of the duties list)
     */
    record RoleView(
            UUID roleId,
            UUID ownerEntityId,
            String nameEn,
            String nameSi,
            String nameTa,
            boolean template,
            String roleClass,
            UUID templateRoleId,
            Integer templateVersionSeen,
            Integer templateVersion,
            boolean templateUpdated,
            int version,
            String status,
            List<RolePermissionView> permissions) {}

    /**
     * What a clone and its template disagree on, as the duties list shows it.
     *
     * @param onlyInTemplate permissions the template has and the role has not, sorted
     * @param onlyInRole     permissions the role has and the template has not, sorted
     * @param limitsDiffer   permissions both have with different limits, sorted
     */
    record RoleDiff(
            UUID roleId,
            UUID templateRoleId,
            Integer templateVersionSeen,
            int templateVersion,
            boolean templateUpdated,
            List<String> onlyInTemplate,
            List<String> onlyInRole,
            List<String> limitsDiffer) {}

    /** @param ownerEntityId null for a federation default */
    record SodPairView(UUID sodPairId, String permissionA, String permissionB, String mode, UUID ownerEntityId) {}

    record AssignmentView(UUID userId, UUID roleId, UUID scopeEntityId, UUID scopeLocationId) {}
}
