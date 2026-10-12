package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads of the audit records whose event type has severity REVIEW, for the module that raised
 * them (CR-19A-14). The audit log stays insert-only for the application: the two reads go
 * through narrow SECURITY DEFINER functions (kernel V0093) that answer an OWN caller with its own
 * entity's rows and a FEDERATION_VIEW caller with every entity's, and nothing to any other class.
 *
 * <p>The kernel knows no module's review codes: the caller names the event types it asks for (M3
 * passes its three pricing reviews), and the function answers only those of them that are
 * REVIEW types in {@code kernel.audit_event_type}.
 */
public interface AuditReviewReader {

    /** The newest first, at most {@code limit} (1 to 1000), of the given REVIEW event types. */
    List<AuditReviewRecord> listReviews(Set<String> eventTypes, int limit, ScopeContext scope);

    /**
     * Whether a review of the type about the subject is already recorded in the caller's scope,
     * with {@code detailKey} in its after-state equal to {@code detailValue} (a replay key).
     */
    boolean reviewExists(String eventType, UUID subjectId, String detailKey, String detailValue, ScopeContext scope);
}
