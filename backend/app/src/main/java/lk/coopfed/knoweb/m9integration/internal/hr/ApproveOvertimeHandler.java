package lk.coopfed.knoweb.m9integration.internal.hr;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.ApproveOvertime;
import lk.coopfed.knoweb.m9integration.api.OvertimeApproved;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "int.overtime.approve")
public class ApproveOvertimeHandler implements Handles<ApproveOvertime, UUID> {

    public static final String AUDIT_APPROVED = "OVERTIME_APPROVED";
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public ApproveOvertimeHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    private record OtCalcMeta(
            UUID calculationId,
            UUID ownerEntityId,
            UUID employeeId,
            int calculatedOtMinutes,
            BigDecimal calculatedAmount,
            String status) {}

    @Override
    @Transactional
    public UUID handle(ApproveOvertime command, ScopeContext scope) {
        if (scope.userId() == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m9.journal.own_required");
        }
        if (command == null || command.calculationId() == null) {
            throw new ProblemException("request.invalid");
        }
        if (command.payrollPeriod() == null
                || !PERIOD_PATTERN.matcher(command.payrollPeriod()).matches()) {
            throw new ProblemException("m9.overtime.period_invalid");
        }

        UUID ownerEntityId = scope.entityId();
        List<OtCalcMeta> calculations = jdbc.query(
                """
                select calculation_id, owner_entity_id, employee_id, calculated_ot_minutes, calculated_amount, status
                  from integration.overtime_calculation
                 where calculation_id = ? and owner_entity_id = ?
                """,
                (rs, rowNum) -> new OtCalcMeta(
                        (UUID) rs.getObject("calculation_id"),
                        (UUID) rs.getObject("owner_entity_id"),
                        (UUID) rs.getObject("employee_id"),
                        rs.getInt("calculated_ot_minutes"),
                        rs.getBigDecimal("calculated_amount"),
                        rs.getString("status")),
                command.calculationId(),
                ownerEntityId);

        if (calculations.isEmpty()) {
            throw new ProblemException(
                    "m9.overtime.not_found",
                    Map.of("calculationId", command.calculationId().toString()));
        }

        OtCalcMeta calc = calculations.get(0);
        if ("APPROVED".equals(calc.status())) {
            throw new ProblemException(
                    "m9.overtime.already_processed",
                    Map.of("calculationId", command.calculationId().toString()));
        }
        if (!"PENDING_APPROVAL".equals(calc.status())) {
            throw new ProblemException(
                    "m9.overtime.not_qualifying",
                    Map.of("calculationId", command.calculationId().toString()));
        }

        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        UUID contributionId = Ids.next();

        jdbc.update(
                """
                insert into integration.payroll_contribution
                    (contribution_id, owner_entity_id, employee_id, calculation_id, payroll_period,
                     contribution_type, approved_ot_minutes, approved_amount, approved_by, approved_at,
                     approval_notes, status, created_at)
                values (?, ?, ?, ?, ?, 'OVERTIME', ?, ?, ?, ?, ?, 'APPROVED_PENDING_PAYROLL', ?)
                """,
                contributionId,
                ownerEntityId,
                calc.employeeId(),
                calc.calculationId(),
                command.payrollPeriod(),
                calc.calculatedOtMinutes(),
                calc.calculatedAmount(),
                scope.userId(),
                nowTs,
                command.notes(),
                nowTs);

        jdbc.update(
                """
                update integration.overtime_calculation
                   set status = 'APPROVED'
                 where calculation_id = ? and owner_entity_id = ?
                """,
                calc.calculationId(),
                ownerEntityId);

        // PII-free audit record: no names, NIC, phone
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("contributionId", contributionId.toString());
        after.put("calculationId", calc.calculationId().toString());
        after.put("employeeId", calc.employeeId().toString());
        after.put("payrollPeriod", command.payrollPeriod());
        after.put("approvedOtMinutes", calc.calculatedOtMinutes());
        after.put("approvedAmount", calc.calculatedAmount());

        audit.record(AUDIT_APPROVED, Subject.of("payroll_contribution", contributionId), null, after, scope);

        events.publish(new OvertimeApproved(
                contributionId,
                calc.calculationId(),
                calc.employeeId(),
                command.payrollPeriod(),
                calc.calculatedOtMinutes(),
                calc.calculatedAmount(),
                scope.userId(),
                now));

        return contributionId;
    }
}
