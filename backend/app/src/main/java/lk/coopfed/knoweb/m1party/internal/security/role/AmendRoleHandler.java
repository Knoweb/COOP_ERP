package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.AmendRole;
import lk.coopfed.knoweb.m1party.api.RoleChanged;
import lk.coopfed.knoweb.m1party.api.RolePermission;
import lk.coopfed.knoweb.m1party.internal.security.EntityLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AmendRole (21A section 6.1, the pseudocode): guardrails first, then the set replace and the
 * version bump. On a template, amended by the Federation, the version bump is what makes every
 * clone show "template updated" (doc 19 DR-4); nothing is pushed into the clones.
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class AmendRoleHandler implements Handles<AmendRole, UUID> {

    static final String AUDIT = "ROLE_CHANGED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final EntityLock lock;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    AmendRoleHandler(
            RoleGuards guards,
            SecurityRecords records,
            EntityLock lock,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.guards = guards;
        this.records = records;
        this.lock = lock;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendRole command, ScopeContext scope) {
        guards.requireEntityWide(scope);
        // The holder guards below count and then write: one security command of the entity at a time.
        lock.lock(scope.entityId());
        SecurityRecords.RoleRow role = guards.role(command.roleId());
        guards.requireOwner(role, scope);
        guards.requireActive(role);

        List<RolePermission> wanted = command.permissions() == null ? List.of() : command.permissions();
        boolean federationOwned = role.ownerEntityId() == null || records.scopeIsFederation();
        guards.permissions(scope, wanted, federationOwned, role.ownerEntityId());

        Map<String, Map<String, Object>> before = records.permissionsOf(role.roleId());
        Map<String, Map<String, Object>> after = new TreeMap<>();
        for (RolePermission permission : wanted) {
            after.putIfAbsent(permission.permissionCode(), permission.limits());
        }

        if (role.ownerEntityId() != null) {
            List<UUID> holders = records.assignmentsAt(role.ownerEntityId(), null).stream()
                    .filter(a -> a.roleId().equals(role.roleId()))
                    .map(SecurityRecords.Assignment::userId)
                    .distinct()
                    .toList();
            guards.noUserConflict(role.ownerEntityId(), holders, role.roleId(), after.keySet());

            if (before.containsKey(SecurityRecords.USER_MANAGE) && !after.containsKey(SecurityRecords.USER_MANAGE)) {
                List<SecurityRecords.Assignment> holdings = records.userManagerHoldings(role.ownerEntityId());
                List<SecurityRecords.Assignment> remaining = holdings.stream()
                        .filter(a -> !a.roleId().equals(role.roleId()))
                        .toList();
                if (RoleRules.losesLastUserManager(holdings, remaining)) {
                    throw new ProblemException("m1.role.last_user_manager", Map.of("roleId", role.roleId()));
                }
            }
        } else {
            requireTemplateChangeSafeForItsHolders(role, before, after);
        }

        Integer templateVersionSeen = role.templateVersionSeen();
        if (command.adoptTemplateVersion()) {
            if (role.templateRoleId() == null) {
                throw new ProblemException("m1.role.no_template", Map.of("roleId", role.roleId()));
            }
            templateVersionSeen = records.role(role.templateRoleId())
                    .map(SecurityRecords.RoleRow::version)
                    .orElseThrow(() -> new ProblemException(
                            "m1.role.template_not_found", Map.of("templateRoleId", role.templateRoleId())));
        }

        Set<String> removed = before.keySet().stream()
                .filter(code -> !after.containsKey(code))
                .collect(Collectors.toSet());
        for (String code : removed) {
            jdbc.update(
                    "delete from security.role_permission where role_id = ? and permission_code = ?",
                    role.roleId(),
                    code);
        }
        for (Map.Entry<String, Map<String, Object>> permission : after.entrySet()) {
            if (!before.containsKey(permission.getKey())) {
                jdbc.update(
                        "insert into security.role_permission (role_id, permission_code, limits) values (?, ?, cast(? as jsonb))",
                        role.roleId(),
                        permission.getKey(),
                        records.toJson(permission.getValue()));
            } else if (!Objects.equals(before.get(permission.getKey()), permission.getValue())) {
                jdbc.update(
                        "update security.role_permission set limits = cast(? as jsonb) where role_id = ? and permission_code = ?",
                        records.toJson(permission.getValue()),
                        role.roleId(),
                        permission.getKey());
            }
        }
        int version = role.version() + 1;
        jdbc.update(
                "update security.role set version = ?, template_version_seen = ? where role_id = ?",
                version,
                templateVersionSeen,
                role.roleId());

        Map<String, List<String>> diff = RoleRules.diff(before.keySet(), after.keySet());
        audit.record(
                AUDIT,
                Subject.of("role", role.roleId()),
                state(role.version(), role.templateVersionSeen(), before),
                state(version, templateVersionSeen, after),
                scope);
        events.publish(new RoleChanged(
                role.roleId(),
                role.ownerEntityId(),
                version,
                role.status(),
                role.template(),
                RoleChanged.AMENDED,
                diff.get("added"),
                diff.get("removed")));
        return role.roleId();
    }

    /**
     * A template is assigned directly in every entity (AssignRole), and the Federation cannot
     * read those entities' users to run the guards above on them. So while anybody outside the
     * Federation holds the template (m1security V0013), two changes are refused: dropping
     * gov.user.manage, which could take a society's last user manager away in one commit (doc
     * 19 section 3.2, GUARDRAIL), and adding a FEDERATION-scope code, which would hand it to
     * every society administrator (doc 21 section 3.5). The Federation retires or clones instead.
     */
    private void requireTemplateChangeSafeForItsHolders(
            SecurityRecords.RoleRow role,
            Map<String, Map<String, Object>> before,
            Map<String, Map<String, Object>> after) {
        boolean dropsUserManage =
                before.containsKey(SecurityRecords.USER_MANAGE) && !after.containsKey(SecurityRecords.USER_MANAGE);
        List<String> added = after.keySet().stream()
                .filter(code -> !before.containsKey(code))
                .toList();
        List<String> federationAdded = records.catalogue(added).values().stream()
                .filter(SecurityRecords.CatalogueEntry::isFederationScope)
                .map(SecurityRecords.CatalogueEntry::code)
                .sorted()
                .toList();
        if (!dropsUserManage && federationAdded.isEmpty()) {
            return;
        }
        long heldOutside = records.templateAssignmentsOutsideFederation(role.roleId());
        if (heldOutside > 0) {
            throw new ProblemException(
                    "m1.role.template_held_outside_federation",
                    Map.of(
                            "roleId",
                            role.roleId(),
                            "permissions",
                            dropsUserManage ? SecurityRecords.USER_MANAGE : String.join(", ", federationAdded),
                            "assignments",
                            heldOutside));
        }
    }

    private static Map<String, Object> state(
            int version, Integer templateVersionSeen, Map<String, Map<String, Object>> permissions) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", version);
        state.put("templateVersionSeen", templateVersionSeen);
        state.put("permissions", new LinkedHashMap<>(permissions));
        return state;
    }
}
