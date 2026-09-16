package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LoggingAuditFacade implements AuditFacade {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditFacade.class);

    @Override
    public void record(
            String eventType,
            Subject subject,
            Object before,
            Object after,
            ScopeContext scope,
            String reason,
            UUID witnessUserId) {
        log.info(
                "AUDIT_STUB eventType={} subjectType={} subjectId={} entityId={} locationId={} correlationId={} reason={} witness={}",
                eventType,
                subject == null ? null : subject.type(),
                subject == null ? null : subject.id(),
                scope == null ? null : scope.entityId(),
                scope == null ? null : scope.locationId(),
                scope == null ? null : scope.correlationId(),
                reason,
                witnessUserId);
    }
}