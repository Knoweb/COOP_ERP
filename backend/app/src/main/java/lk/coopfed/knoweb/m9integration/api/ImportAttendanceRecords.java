package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Import punch records from fingerprint biometric attendance devices.
 */
public record ImportAttendanceRecords(List<AttendanceImportItem> items) {

    public record AttendanceImportItem(
            String externalRecordId,
            String employeeNo,
            String deviceIdentifier,
            String sourceProvider,
            LocalDate businessDate,
            Instant clockIn,
            Instant clockOut,
            String rawPayload) {}
}
