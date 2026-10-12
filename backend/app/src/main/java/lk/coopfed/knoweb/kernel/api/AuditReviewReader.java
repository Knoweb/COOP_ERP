package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.UUID;

public interface AuditReviewReader {

    List<AuditReviewRecord> listReviews(String kind, Integer limit, ScopeContext scope);

    default List<AuditReviewRecord> listReviews(String kind, ScopeContext scope) {
        return listReviews(kind, 100, scope);
    }

    boolean reviewExists(String eventType, UUID subjectId, String detailKey, String detailValue, ScopeContext scope);

    default boolean reviewExists(String eventType, UUID subjectId, String detailKey, String detailValue) {
        return reviewExists(eventType, subjectId, detailKey, detailValue, null);
    }
}
