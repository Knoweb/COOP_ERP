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
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    AmendRoleHandler(
            RoleGuards guards, SecurityRecords records, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.guards = guards;
        this.records = records;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendRole command, ScopeContext scope) {
        guards.requireEntityWide(scope);
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

    private static Map<String, Object> state(
            int version, Integer templateVersionSeen, Map<String, Map<String, Object>> permissions) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", version);
        state.put("templateVersionSeen", templateVersionSeen);
        state.put("permissions", new LinkedHashMap<>(permissions));
        return state;
    }
}
