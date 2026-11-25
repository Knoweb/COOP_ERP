package lk.coopfed.knoweb.m9integration.api;

import java.util.List;
import java.util.UUID;

/**
 * Result of batch attendance import.
 */
public record AttendanceImportResult(
        int imported, int duplicate, int rejected, List<UUID> recordIds, List<AttendanceRejection> rejections) {

    public record AttendanceRejection(String externalRecordId, String reason) {}
}
