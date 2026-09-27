package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * A PDF was rendered and stored (19A section 6: "Output to object storage; report.rendered.v1").
 * Carries where it is and what it is, never the data it was filled with.
 */
public record ReportRendered(
        UUID reportId,
        String templateId,
        String language,
        String objectKey,
        long sizeBytes,
        String contentHash,
        UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "report.rendered.v1";
}
