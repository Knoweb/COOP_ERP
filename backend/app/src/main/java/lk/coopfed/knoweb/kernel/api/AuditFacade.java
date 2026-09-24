package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

public interface AuditFacade {

    void record(
            String eventType,
            Subject subject,
            Object before,
            Object after,
            ScopeContext scope,
            String reason,
            UUID witnessUserId);

    default void record(
            String eventType, Subject subject, Object before, Object after, ScopeContext scope, String reason) {
        record(eventType, subject, before, after, scope, reason, null);
    }

    default void record(String eventType, Subject subject, Object before, Object after, ScopeContext scope) {
        record(eventType, subject, before, after, scope, null, null);
    }
}
