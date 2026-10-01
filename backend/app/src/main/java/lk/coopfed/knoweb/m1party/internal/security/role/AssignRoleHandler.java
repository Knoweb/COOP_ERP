package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.AssignRole;
import lk.coopfed.knoweb.m1party.api.RoleAssigned;
import lk.coopfed.knoweb.m1party.internal.security.EntityLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AssignRole (21A section 6: "role owner = scope entity or template; scope within caller's
 * scope; user in scope; role class permits"; doc 21 flow 6.3: "AssignRole scoped to the shop
 * location"). Two guards beyond the table, both from doc 19 section 3.2: assigning is granting,
 * so the grantor must hold every permission of the role; and a person may not come to hold both
 * halves of a pair in ROLE mode through two roles.
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class AssignRoleHandler implements Handles<AssignRole, UUID> {

    static final String AUDIT = "ROLE_ASSIGNED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final EntityLock lock;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    AssignRoleHandler(
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
    public UUID handle(AssignRole command, ScopeContext scope) {
        guards.requireOwnScope(scope);
        UUID entityId = scope.entityId();
        // The per-person pair check reads what the user holds and then writes: one command of
        // the entity at a time, or two assignments could each pass and together give both halves.
        // We lock the target entity where the role is assigned.
        SecurityRecords.RoleRow role = guards.role(command.roleId());

        if (scope.locationId() != null && !Objects.equals(scope.locationId(), command.scopeLocationId())) {
            throw new ProblemException(
                    "m1.assignment.outside_caller_scope",
                    Map.of("scopeLocationId", String.valueOf(command.scopeLocationId())));
        }

        SecurityRecords.UserRow user = records.user(command.userId())
                .orElseThrow(() -> new ProblemException(
                        "m1.assignment.user_not_in_scope", Map.of("userId", String.valueOf(command.userId()))));

        UUID targetEntityId = user.homeEntityId();
        if (!entityId.equals(targetEntityId)) {
            // Distributor can assign roles in the MPCS they manage
            Boolean isDistributor = jdbc.queryForObject(
                    "select exists (select 1 from party.entity where entity_id = ? and managing_distributor_id = ?)",
                    Boolean.class,
                    targetEntityId,
                    entityId);
            if (!Boolean.TRUE.equals(isDistributor)) {
                throw new ProblemException(
                        "m1.assignment.user_not_in_scope", Map.of("userId", String.valueOf(command.userId())));
            }
        }

        lock.lock(targetEntityId);

        boolean ownRole = targetEntityId.equals(role.ownerEntityId());
        boolean template = role.template() && role.ownerEntityId() == null;
        if (!ownRole && !template) {
            throw new ProblemException("m1.assignment.role_not_in_scope", Map.of("roleId", role.roleId()));
        }
        guards.requireActive(role);

        if (command.scopeLocationId() != null
                && !records.locationOwner(command.scopeLocationId())
                        .map(targetEntityId::equals)
                        .orElse(false)) {
            throw new ProblemException(
                    "m1.assignment.location_not_in_entity", Map.of("scopeLocationId", command.scopeLocationId()));
        }

        if ("DEACTIVATED".equals(user.status())) {
            throw new ProblemException("m1.assignment.user_deactivated", Map.of("userId", user.userId()));
        }

        // A FEDERATION_VIEW role is the Federation's to give its own staff. An EXTERNAL_TIMEBOXED
        // role (the Regulator and Auditor templates) is the Federation's to give an EXTERNAL user
        // it created (doc 21 flow 6.5): the role says what the regulator may read, the external
        // grant of M1-09 says in which entities and until when; the kernel resolves the two
        // together (CR-19A-9, decided 27 September 2026). Nobody else assigns either class.
        boolean federation = records.scopeIsFederation();
        boolean classPermits = "OWN".equals(role.roleClass())
                || ("FEDERATION_VIEW".equals(role.roleClass()) && federation)
                || ("EXTERNAL_TIMEBOXED".equals(role.roleClass()) && federation && "EXTERNAL".equals(user.userKind()));
        if (!classPermits) {
            throw new ProblemException(
                    "m1.assignment.class_not_permitted",
                    Map.of("roleId", role.roleId(), "roleClass", role.roleClass()));
        }

        SecurityRecords.Assignment assignment =
                new SecurityRecords.Assignment(user.userId(), role.roleId(), targetEntityId, command.scopeLocationId());
        if (records.assignmentExists(assignment)) {
            throw new ProblemException(
                    "m1.assignment.exists", Map.of("userId", user.userId(), "roleId", role.roleId()));
        }

        Set<String> rolePermissions = records.permissionsOf(role.roleId()).keySet();
        guards.withinGrantor(scope, rolePermissions);

        // What the user holds anywhere in the entity, not only where this caller may see
        // (m1security V0013): a half held at another shop still counts.
        List<String> wouldHold = new ArrayList<>(records.permissionsAt(targetEntityId, user.userId(), null));
        wouldHold.addAll(rolePermissions);
        guards.roleModeConflict(wouldHold, targetEntityId, "m1.assignment.sod_conflict", user.userId());

        jdbc.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                user.userId(),
                role.roleId(),
                targetEntityId,
                command.scopeLocationId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleId", role.roleId());
        after.put("scopeEntityId", targetEntityId);
        after.put("scopeLocationId", command.scopeLocationId());
        audit.record(AUDIT, Subject.of("user", user.userId()), null, after, scope);
        events.publish(new RoleAssigned(role.roleId(), user.userId(), targetEntityId, command.scopeLocationId()));
        return role.roleId();
    }
}
