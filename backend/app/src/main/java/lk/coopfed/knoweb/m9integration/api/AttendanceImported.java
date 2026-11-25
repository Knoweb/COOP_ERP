package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * integration.attendance_imported.v1: biometric attendance punch records were successfully imported.
 */
public record AttendanceImported(
        UUID batchId,
        UUID ownerEntityId,
        String sourceProvider,
        int importedCount,
        int duplicateCount,
        int rejectedCount,
        Instant importedAt)
        implements DomainEvent {

    public static final String TYPE = "integration.attendance_imported.v1";
}
