package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.CreateRole;
import lk.coopfed.knoweb.m1party.api.RoleChanged;
import lk.coopfed.knoweb.m1party.api.RolePermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CreateRole (21A section 6; doc 21 flow 6.3: "MPCS admin clones the shop-in-charge template").
 * A clone is the entity's own role from that moment (doc 19 section 3.3) and remembers the
 * template and the template's version it was made from, which is what the drift diff compares.
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class CreateRoleHandler implements Handles<CreateRole, UUID> {

    static final String AUDIT = "ROLE_CHANGED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateRoleHandler(
            RoleGuards guards, SecurityRecords records, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.guards = guards;
        this.records = records;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreateRole command, ScopeContext scope) {
        guards.requireEntityWide(scope);
        boolean federation = records.scopeIsFederation();
        if (command.asTemplate() && !federation) {
            throw new ProblemException("m1.role.federation_required");
        }
        UUID owner = command.asTemplate() ? null : scope.entityId();

        String nameEn = command.nameEn() == null ? "" : command.nameEn().strip();
        if (nameEn.isEmpty()) {
            throw new ProblemException("m1.role.name_required");
        }
        if (records.nameTaken(owner, nameEn)) {
            throw new ProblemException("m1.role.name_taken", Map.of("name", nameEn));
        }

        SecurityRecords.RoleRow template = null;
        List<RolePermission> wanted = command.permissions() == null ? List.of() : command.permissions();
        if (command.templateRoleId() != null) {
            template = records.role(command.templateRoleId())
                    .filter(role -> role.template() && role.ownerEntityId() == null && role.isActive())
                    .orElseThrow(() -> new ProblemException(
                            "m1.role.template_not_found", Map.of("templateRoleId", command.templateRoleId())));
            if (command.permissions() == null) {
                wanted = new ArrayList<>();
                for (Map.Entry<String, Map<String, Object>> p :
                        records.permissionsOf(template.roleId()).entrySet()) {
                    wanted.add(new RolePermission(p.getKey(), p.getValue()));
                }
            }
        }

        String roleClass =
                command.roleClass() != null ? command.roleClass() : template != null ? template.roleClass() : "OWN";
        boolean federationOwned = command.asTemplate() || federation;
        guards.requireClassPermitted(roleClass, federationOwned);
        guards.permissions(scope, wanted, federationOwned, owner);

        UUID roleId = Ids.next();
        jdbc.update(
                """
                insert into security.role (role_id, owner_entity_id, name_en, name_si, name_ta, is_template,
                                           role_class, template_role_id, template_version_seen, version, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'ACTIVE')
                """,
                roleId,
                owner,
                nameEn,
                blankToNull(command.nameSi()),
                blankToNull(command.nameTa()),
                command.asTemplate(),
                roleClass,
                template == null ? null : template.roleId(),
                template == null ? null : template.version());
        Map<String, Map<String, Object>> written = new LinkedHashMap<>();
        for (RolePermission permission : wanted) {
            if (written.containsKey(permission.permissionCode())) {
                continue;
            }
            jdbc.update(
                    "insert into security.role_permission (role_id, permission_code, limits) values (?, ?, cast(? as jsonb))",
                    roleId,
                    permission.permissionCode(),
                    records.toJson(permission.limits()));
            written.put(permission.permissionCode(), permission.limits());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("nameEn", nameEn);
        after.put("template", command.asTemplate());
        after.put("roleClass", roleClass);
        after.put("templateRoleId", template == null ? null : template.roleId());
        after.put("templateVersionSeen", template == null ? null : template.version());
        after.put("version", 1);
        after.put("status", "ACTIVE");
        after.put("permissions", written);
        audit.record(AUDIT, Subject.of("role", roleId), null, after, scope);

        events.publish(new RoleChanged(
                roleId,
                owner,
                1,
                "ACTIVE",
                command.asTemplate(),
                RoleChanged.CREATED,
                written.keySet().stream().sorted().toList(),
                List.of()));
        return roleId;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
