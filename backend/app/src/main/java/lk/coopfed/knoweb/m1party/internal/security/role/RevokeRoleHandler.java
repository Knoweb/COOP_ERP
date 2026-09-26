package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.RevokeRole;
import lk.coopfed.knoweb.m1party.api.RoleRevoked;
import lk.coopfed.knoweb.m1party.internal.security.EntityLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RevokeRole (21A section 6): deletes the assignment, "the one DELETE M1 performs; audited".
 * The audit record keeps what the row was. The event names the user, so the kernel's
 * permission cache forgets that user's permissions and the next command is checked against
 * what is left (PermissionCacheInvalidator).
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class RevokeRoleHandler implements Handles<RevokeRole, UUID> {

    static final String AUDIT = "ROLE_REVOKED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final EntityLock lock;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RevokeRoleHandler(
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
    public UUID handle(RevokeRole command, ScopeContext scope) {
        guards.requireOwnScope(scope);
        UUID entityId = scope.entityId();
        // The last-user-manager guard counts and then deletes: two revocations at once could each
        // count two holders and together leave none. One command of the entity at a time.
        lock.lock(entityId);

        if (scope.locationId() != null && !Objects.equals(scope.locationId(), command.scopeLocationId())) {
            throw new ProblemException(
                    "m1.assignment.outside_caller_scope",
                    Map.of("scopeLocationId", String.valueOf(command.scopeLocationId())));
        }

        SecurityRecords.Assignment assignment =
                new SecurityRecords.Assignment(command.userId(), command.roleId(), entityId, command.scopeLocationId());
        if (command.userId() == null || command.roleId() == null || !records.assignmentExists(assignment)) {
            throw new ProblemException(
                    "m1.assignment.not_found",
                    Map.of("userId", String.valueOf(command.userId()), "roleId", String.valueOf(command.roleId())));
        }

        List<SecurityRecords.Assignment> holdings = records.userManagerHoldings(entityId);
        List<SecurityRecords.Assignment> remaining =
                holdings.stream().filter(a -> !a.equals(assignment)).toList();
        if (RoleRules.losesLastUserManager(holdings, remaining)) {
            throw new ProblemException("m1.assignment.last_user_manager", Map.of("userId", command.userId()));
        }

        jdbc.update(
                """
                delete from security.user_role
                 where user_id = ? and role_id = ? and scope_entity_id = ?
                   and scope_location_id is not distinct from ?
                """,
                command.userId(),
                command.roleId(),
                entityId,
                command.scopeLocationId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("roleId", command.roleId());
        before.put("scopeEntityId", entityId);
        before.put("scopeLocationId", command.scopeLocationId());
        String reason = command.reason() == null || command.reason().isBlank()
                ? null
                : command.reason().strip();
        audit.record(AUDIT, Subject.of("user", command.userId()), before, null, scope, reason);
        events.publish(new RoleRevoked(command.roleId(), command.userId(), entityId, command.scopeLocationId()));
        return command.roleId();
    }
}
