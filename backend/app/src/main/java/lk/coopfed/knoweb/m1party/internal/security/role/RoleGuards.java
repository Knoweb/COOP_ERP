package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.RolePermission;
import org.springframework.stereotype.Component;

/**
 * The guards the role, assignment and pair handlers share, in the order 21A section 6.1 runs
 * them. Each reads its facts from {@link SecurityRecords} (under the caller's row-level
 * security) and decides with {@link RoleRules}; a failure is a {@link ProblemException} whose id
 * is a message id. It writes nothing.
 *
 * <p>These are what 19A section 3 calls {@code Grant.withinGrantor}, {@code Grant.lastAdminGuard}
 * and {@code Sod.roleModeConflict}: the kernel declares none of them (their parameters are M1
 * types, kernel.api.Sod says), so they live here, beside the tables they read.
 */
@Component
class RoleGuards {

    static final List<String> ROLE_CLASSES = List.of("OWN", "FEDERATION_VIEW", "EXTERNAL_TIMEBOXED");

    private final SecurityRecords records;
    private final PermissionResolver resolver;

    RoleGuards(SecurityRecords records, PermissionResolver resolver) {
        this.records = records;
        this.resolver = resolver;
    }

    /**
     * Roles are authored for the whole entity, in the OWN class: a location-scoped session, a
     * read-only class or a request with no scope is refused.
     */
    void requireEntityWide(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.policyClass() != PolicyClass.OWN
                || scope.locationId() != null) {
            throw new ProblemException("m1.role.entity_scope_required");
        }
    }

    /** Assignments may be made at a location by a caller scoped to that location. */
    void requireOwnScope(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m1.role.entity_scope_required");
        }
    }

    SecurityRecords.RoleRow role(UUID roleId) {
        return records.role(roleId)
                .orElseThrow(() -> new ProblemException("m1.role.not_found", Map.of("roleId", String.valueOf(roleId))));
    }

    /**
     * May this caller change this role: its own entity's role, or a template when the caller is
     * the Federation (21A section 6.1: "role.not_owner").
     */
    void requireOwner(SecurityRecords.RoleRow role, ScopeContext scope) {
        boolean own = role.ownerEntityId() != null && role.ownerEntityId().equals(scope.entityId());
        boolean federationTemplate = role.template() && role.ownerEntityId() == null && records.scopeIsFederation();
        if (!own && !federationTemplate) {
            throw new ProblemException("m1.role.not_owner", Map.of("roleId", role.roleId()));
        }
    }

    void requireActive(SecurityRecords.RoleRow role) {
        if (!role.isActive()) {
            throw new ProblemException("m1.role.retired", Map.of("roleId", role.roleId()));
        }
    }

    /** A class other than OWN belongs to the Federation: its templates and its own roles. */
    void requireClassPermitted(String roleClass, boolean federation) {
        if (!ROLE_CLASSES.contains(roleClass)) {
            throw new ProblemException("m1.role.class_invalid", Map.of("roleClass", String.valueOf(roleClass)));
        }
        if (!"OWN".equals(roleClass) && !federation) {
            throw new ProblemException("m1.role.class_not_permitted", Map.of("roleClass", roleClass));
        }
    }

    /**
     * The permission guards of CreateRole and AmendRole, in 21A's order: every code is in the
     * catalogue; the grantor holds every one; no FEDERATION-scope code outside a federation-owned
     * role; no pair in ROLE mode; limits valid against each permission's schema.
     *
     * @param federationOwned the role is a template or one of the Federation's own
     * @param sodEntity       the entity whose pairs apply; null for a template (defaults only)
     */
    void permissions(ScopeContext scope, Collection<RolePermission> wanted, boolean federationOwned, UUID sodEntity) {
        List<String> codes = RoleRules.codes(wanted);
        Map<String, SecurityRecords.CatalogueEntry> catalogue = records.catalogue(codes);

        List<String> unknown = codes.stream()
                .filter(code -> !catalogue.containsKey(code))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new ProblemException("m1.role.permission_unknown", Map.of("permissions", String.join(", ", unknown)));
        }

        withinGrantor(scope, codes);

        List<String> federationOnly = RoleRules.federationOnly(catalogue.values(), federationOwned);
        if (!federationOnly.isEmpty()) {
            throw new ProblemException(
                    "m1.role.permission_federation_only", Map.of("permissions", String.join(", ", federationOnly)));
        }

        roleModeConflict(codes, sodEntity, "m1.role.sod_conflict", null);

        for (RolePermission permission : wanted) {
            Optional<String> problem = RoleRules.limitsProblem(
                    permission.limits(),
                    catalogue.get(permission.permissionCode()).limitsSchema());
            if (problem.isPresent()) {
                throw new ProblemException(
                        catalogue.get(permission.permissionCode()).limitsSchema() == null
                                ? "m1.role.limits_not_accepted"
                                : "m1.role.limits_invalid",
                        Map.of("permission", permission.permissionCode(), "field", problem.get()));
            }
        }
    }

    /** "Nobody can grant a permission they do not themselves hold" (doc 19 section 3.2). */
    void withinGrantor(ScopeContext scope, Collection<String> codes) {
        Set<String> held = resolver.resolve(scope);
        List<String> notHeld = RoleRules.notHeld(codes, held);
        if (!notHeld.isEmpty()) {
            throw new ProblemException(
                    "m1.role.permission_not_held", Map.of("permissions", String.join(", ", notHeld)));
        }
    }

    /** Refuses when the permissions hold both halves of a pair in ROLE mode for the entity. */
    void roleModeConflict(Collection<String> permissions, UUID entityId, String messageId, UUID userId) {
        Optional<List<String>> conflict = RoleRules.roleModeConflict(permissions, records.pairsFor(entityId));
        if (conflict.isPresent()) {
            Map<String, Object> params = userId == null
                    ? Map.of(
                            "permissionA", conflict.get().get(0),
                            "permissionB", conflict.get().get(1))
                    : Map.of(
                            "permissionA", conflict.get().get(0),
                            "permissionB", conflict.get().get(1),
                            "userId", userId);
            throw new ProblemException(messageId, params);
        }
    }

    /**
     * A person, not only a role, may not hold both halves of a ROLE pair (kernel.api.Sod: "a pair
     * in ROLE mode forbids one person holding both permissions at all"). Checks each of these
     * users with what they would hold at the entity once the role holds {@code permissions}.
     */
    void noUserConflict(UUID entityId, Collection<UUID> users, UUID roleId, Collection<String> permissions) {
        Map<UUID, Set<String>> holdings = records.holdingsAt(entityId, users, roleId);
        for (Map.Entry<UUID, Set<String>> holding : holdings.entrySet()) {
            List<String> would = new ArrayList<>(holding.getValue());
            would.addAll(permissions);
            roleModeConflict(would, entityId, "m1.role.sod_conflict", holding.getKey());
        }
    }
}
