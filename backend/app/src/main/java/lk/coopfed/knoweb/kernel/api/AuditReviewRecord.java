package lk.coopfed.knoweb.kernel.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditReviewRecord(
        UUID auditId,
        String eventTypeCode,
        OffsetDateTime occurredAt,
        UUID ownerEntityId,
        UUID subjectId,
        String afterState) {}
