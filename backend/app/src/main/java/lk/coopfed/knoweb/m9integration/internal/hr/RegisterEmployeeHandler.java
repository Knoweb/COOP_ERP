package lk.coopfed.knoweb.m9integration.internal.hr;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.EmployeeRegistered;
import lk.coopfed.knoweb.m9integration.api.RegisterEmployee;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "int.employee.manage")
public class RegisterEmployeeHandler implements Handles<RegisterEmployee, UUID> {

    public static final String AUDIT_REGISTERED = "EMPLOYEE_REGISTERED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public RegisterEmployeeHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RegisterEmployee command, ScopeContext scope) {
        if (scope.userId() == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m9.journal.own_required");
        }
        if (command == null
                || command.employeeNo() == null
                || command.employeeNo().isBlank()) {
            throw new ProblemException("request.invalid");
        }
        if (command.fullName() == null || command.fullName().isBlank()) {
            throw new ProblemException("request.invalid");
        }
        if (command.hourlyRate() == null || command.hourlyRate().compareTo(BigDecimal.ZERO) < 0) {
            throw new ProblemException("request.invalid");
        }

        UUID ownerEntityId = scope.entityId();
        String employeeNo = command.employeeNo().trim();
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);

        List<UUID> existing = jdbc.query(
                "select employee_id from integration.hr_employee where owner_entity_id = ? and employee_no = ?",
                (rs, rowNum) -> (UUID) rs.getObject("employee_id"),
                ownerEntityId,
                employeeNo);

        UUID employeeId;
        if (!existing.isEmpty()) {
            employeeId = existing.get(0);
            jdbc.update(
                    """
                    update integration.hr_employee
                       set full_name = ?,
                           nic = ?,
                           epf_no = ?,
                           hourly_rate = ?,
                           location_id = ?,
                           updated_at = ?
                     where employee_id = ? and owner_entity_id = ?
                    """,
                    command.fullName().trim(),
                    command.nic() != null ? command.nic().trim() : null,
                    command.epfNo() != null ? command.epfNo().trim() : null,
                    command.hourlyRate(),
                    command.locationId(),
                    nowTs,
                    employeeId,
                    ownerEntityId);
        } else {
            employeeId = Ids.next();
            jdbc.update(
                    """
                    insert into integration.hr_employee
                        (employee_id, owner_entity_id, employee_no, full_name, nic, epf_no, hourly_rate, location_id, status, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """,
                    employeeId,
                    ownerEntityId,
                    employeeNo,
                    command.fullName().trim(),
                    command.nic() != null ? command.nic().trim() : null,
                    command.epfNo() != null ? command.epfNo().trim() : null,
                    command.hourlyRate(),
                    command.locationId(),
                    nowTs,
                    nowTs);
        }

        // PII-free audit record: no personal identifiers like NIC or phone
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("employeeId", employeeId.toString());
        after.put("employeeNo", employeeNo);
        after.put("hourlyRate", command.hourlyRate());
        if (command.locationId() != null) {
            after.put("locationId", command.locationId().toString());
        }
        after.put("status", "ACTIVE");

        audit.record(AUDIT_REGISTERED, Subject.of("hr_employee", employeeId), null, after, scope);

        events.publish(new EmployeeRegistered(employeeId, employeeNo, ownerEntityId, command.locationId(), now));

        return employeeId;
    }
}
