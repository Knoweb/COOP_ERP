package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.RetireRole;
import lk.coopfed.knoweb.m1party.api.RoleChanged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RetireRole (21A section 6): only a role nobody holds, so that retiring never silently takes a
 * permission away from somebody; revoke the assignments first. The role and its permissions stay
 * for history; the resolver reads ACTIVE roles only.
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class RetireRoleHandler implements Handles<RetireRole, UUID> {

    static final String AUDIT = "ROLE_CHANGED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RetireRoleHandler(
            RoleGuards guards, SecurityRecords records, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.guards = guards;
        this.records = records;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RetireRole command, ScopeContext scope) {
        guards.requireEntityWide(scope);
        SecurityRecords.RoleRow role = guards.role(command.roleId());
        guards.requireOwner(role, scope);
        guards.requireActive(role);

        long assignments = records.assignmentCount(role.roleId());
        if (assignments > 0) {
            throw new ProblemException(
                    "m1.role.has_assignments", Map.of("roleId", role.roleId(), "count", assignments));
        }

        // The version is left alone: it counts changes to the permission set, and a retired
        // template must not show every clone a "template updated" marker with nothing to adopt.
        jdbc.update("update security.role set status = 'RETIRED' where role_id = ?", role.roleId());

        audit.record(
                AUDIT,
                Subject.of("role", role.roleId()),
                Map.of("status", role.status()),
                Map.of("status", "RETIRED"),
                scope);
        events.publish(new RoleChanged(
                role.roleId(),
                role.ownerEntityId(),
                role.version(),
                "RETIRED",
                role.template(),
                RoleChanged.RETIRED,
                List.of(),
                List.of()));
        return role.roleId();
    }
}
