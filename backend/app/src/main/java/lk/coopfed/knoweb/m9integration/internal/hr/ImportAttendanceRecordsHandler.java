package lk.coopfed.knoweb.m9integration.internal.hr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.AttendanceImportResult;
import lk.coopfed.knoweb.m9integration.api.AttendanceImportResult.AttendanceRejection;
import lk.coopfed.knoweb.m9integration.api.AttendanceImported;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords.AttendanceImportItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "int.attendance.import")
public class ImportAttendanceRecordsHandler implements Handles<ImportAttendanceRecords, AttendanceImportResult> {

    public static final String AUDIT_IMPORTED = "ATTENDANCE_IMPORTED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ConfigRegistry config;
    private final Clock clock;

    public ImportAttendanceRecordsHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, ConfigRegistry config, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.config = config;
        this.clock = clock;
    }

    private record EmployeeMeta(UUID employeeId, BigDecimal hourlyRate) {}

    @Override
    @Transactional
    public AttendanceImportResult handle(ImportAttendanceRecords command, ScopeContext scope) {
        if (scope.userId() == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m9.journal.own_required");
        }
        if (command == null || command.items() == null) {
            throw new ProblemException("request.invalid");
        }

        UUID ownerEntityId = scope.entityId();
        int standardDailyMinutes = config.getInt("hr.attendance.standard_daily_minutes", scope, 480);
        int overtimeThresholdMinutes = config.getInt("hr.attendance.overtime_threshold_minutes", scope, 30);
        String stdMultStr = config.getOrDefault("hr.attendance.overtime_rate_multiplier", scope, "1.50");
        BigDecimal standardMultiplier = new BigDecimal(stdMultStr != null ? stdMultStr : "1.50");
        String wkMultStr = config.getOrDefault("hr.attendance.weekend_rate_multiplier", scope, "2.00");
        BigDecimal weekendMultiplier = new BigDecimal(wkMultStr != null ? wkMultStr : "2.00");
        List<UUID> importedIds = new ArrayList<>();
        List<AttendanceRejection> rejections = new ArrayList<>();
        int duplicates = 0;
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);

        for (AttendanceImportItem item : command.items()) {
            if (item == null || item.externalRecordId() == null || item.employeeNo() == null) {
                continue;
            }

            // Guard: clockOut cannot be before clockIn
            if (item.clockIn() == null
                    || item.clockOut() == null
                    || item.clockOut().isBefore(item.clockIn())) {
                rejections.add(new AttendanceRejection(item.externalRecordId(), "m9.attendance.clock_times_invalid"));
                continue;
            }

            // Deduplication check against device punch sequence
            List<UUID> existing = jdbc.query(
                    """
                    select attendance_id from integration.attendance_record
                     where owner_entity_id = ? and source_provider = ? and external_record_id = ?
                    """,
                    (rs, rowNum) -> (UUID) rs.getObject("attendance_id"),
                    ownerEntityId,
                    item.sourceProvider(),
                    item.externalRecordId());

            if (!existing.isEmpty()) {
                duplicates++;
                continue;
            }

            // Employee lookup by employee_no within owner entity scope
            List<EmployeeMeta> employees = jdbc.query(
                    """
                    select employee_id, hourly_rate from integration.hr_employee
                     where owner_entity_id = ? and employee_no = ? and status = 'ACTIVE'
                    """,
                    (rs, rowNum) ->
                            new EmployeeMeta((UUID) rs.getObject("employee_id"), rs.getBigDecimal("hourly_rate")),
                    ownerEntityId,
                    item.employeeNo().trim());

            if (employees.isEmpty()) {
                rejections.add(new AttendanceRejection(item.externalRecordId(), "m9.employee.not_found"));
                continue;
            }

            EmployeeMeta employee = employees.get(0);
            int workedMinutes =
                    (int) Duration.between(item.clockIn(), item.clockOut()).toMinutes();
            UUID attendanceId = Ids.next();

            jdbc.update(
                    """
                    insert into integration.attendance_record
                        (attendance_id, owner_entity_id, external_record_id, employee_id, device_identifier,
                         source_provider, business_date, clock_in, clock_out, worked_minutes, status,
                         raw_payload, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALIDATED', ?::jsonb, ?)
                    """,
                    attendanceId,
                    ownerEntityId,
                    item.externalRecordId(),
                    employee.employeeId(),
                    item.deviceIdentifier(),
                    item.sourceProvider(),
                    Date.valueOf(item.businessDate()),
                    Timestamp.from(item.clockIn()),
                    Timestamp.from(item.clockOut()),
                    workedMinutes,
                    item.rawPayload() != null ? item.rawPayload() : "{}",
                    nowTs);

            // Overtime Calculation
            DayOfWeek dow = item.businessDate().getDayOfWeek();
            boolean isWeekend = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);
            BigDecimal multiplier = isWeekend ? weekendMultiplier : standardMultiplier;

            int excessMinutes = Math.max(0, workedMinutes - standardDailyMinutes);
            int qualifyingOtMinutes = 0;
            int calculatedOtMinutes = 0;
            BigDecimal calculatedAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            String otStatus = "NO_OT_EARNED";

            if (excessMinutes >= overtimeThresholdMinutes) {
                qualifyingOtMinutes = excessMinutes;
                calculatedOtMinutes = excessMinutes;
                otStatus = "PENDING_APPROVAL";
                calculatedAmount = employee.hourlyRate()
                        .multiply(multiplier)
                        .multiply(BigDecimal.valueOf(calculatedOtMinutes))
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
            }

            UUID calculationId = Ids.next();
            jdbc.update(
                    """
                    insert into integration.overtime_calculation
                        (calculation_id, attendance_id, owner_entity_id, employee_id, business_date,
                         worked_minutes, standard_minutes, qualifying_ot_minutes, calculated_ot_minutes,
                         ot_multiplier, calculated_amount, status, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    calculationId,
                    attendanceId,
                    ownerEntityId,
                    employee.employeeId(),
                    Date.valueOf(item.businessDate()),
                    workedMinutes,
                    standardDailyMinutes,
                    qualifyingOtMinutes,
                    calculatedOtMinutes,
                    multiplier,
                    calculatedAmount,
                    otStatus,
                    nowTs);

            importedIds.add(attendanceId);
        }

        // PII-free audit summary
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("importedCount", importedIds.size());
        after.put("duplicateCount", duplicates);
        after.put("rejectedCount", rejections.size());

        audit.record(AUDIT_IMPORTED, Subject.of("attendance_import", ownerEntityId), null, after, scope);

        if (!importedIds.isEmpty()) {
            String sourceProvider = command.items().stream()
                    .filter(i -> i != null && i.sourceProvider() != null)
                    .map(AttendanceImportItem::sourceProvider)
                    .findFirst()
                    .orElse("BIOMETRIC");
            UUID batchId = Ids.next();
            events.publish(new AttendanceImported(
                    batchId, ownerEntityId, sourceProvider, importedIds.size(), duplicates, rejections.size(), now));
        }

        return new AttendanceImportResult(importedIds.size(), duplicates, rejections.size(), importedIds, rejections);
    }
}
