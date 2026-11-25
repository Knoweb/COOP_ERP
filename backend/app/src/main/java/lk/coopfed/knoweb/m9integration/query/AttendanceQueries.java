package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Published queries for attendance and overtime calculations.
 */
public interface AttendanceQueries {

    List<AttendanceRecordView> listAttendance(LocalDate businessDate, UUID employeeId, ScopeContext scope);

    Optional<AttendanceRecordView> getAttendance(UUID attendanceId, ScopeContext scope);

    List<OvertimeCalculationView> listOvertime(String status, LocalDate businessDate, ScopeContext scope);

    Optional<OvertimeCalculationView> getOvertime(UUID calculationId, ScopeContext scope);

    record AttendanceRecordView(
            UUID attendanceId,
            UUID ownerEntityId,
            String externalRecordId,
            UUID employeeId,
            String employeeNo,
            String employeeName,
            String deviceIdentifier,
            String sourceProvider,
            LocalDate businessDate,
            Instant clockIn,
            Instant clockOut,
            int workedMinutes,
            String status,
            String rejectionReason,
            Instant createdAt) {}

    record OvertimeCalculationView(
            UUID calculationId,
            UUID attendanceId,
            UUID ownerEntityId,
            UUID employeeId,
            String employeeNo,
            String employeeName,
            LocalDate businessDate,
            int workedMinutes,
            int standardMinutes,
            int qualifyingOtMinutes,
            int calculatedOtMinutes,
            BigDecimal otMultiplier,
            BigDecimal calculatedAmount,
            String status,
            Instant createdAt) {}
}
