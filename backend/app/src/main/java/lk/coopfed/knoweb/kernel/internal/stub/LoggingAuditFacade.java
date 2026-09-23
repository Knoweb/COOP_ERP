package lk.coopfed.knoweb.kernel.internal.stub;

import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 17A stub of the audit service: it writes a log line, not a row. 19A ticket K-04 replaces
 * it with the insert into kernel.audit_event; callers do not change.
 *
 * <p>It is strict on purpose. Everything the real table will refuse, this stub refuses
 * today, so that a mistake shows on the developer's first run and not when K-04 lands:
 * <ul>
 *   <li>the event type is a catalogue code: capitals, digits and underscores, at most 40
 *       characters (kernel.audit_event_type.event_type_code is varchar(40))</li>
 *   <li>the subject names what was changed: a type and an id (both NOT NULL in the table)</li>
 *   <li>the scope has an active entity (owner_entity_id is NOT NULL)</li>
 *   <li>a transaction is open: the audit record must commit or roll back with the change it
 *       describes, which is only true inside the handler's transaction</li>
 * </ul>
 * These are programming errors, not business rules, so they are plain exceptions and not
 * a ProblemException: no user can fix them.
 */
@Component
public class LoggingAuditFacade implements AuditFacade {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditFacade.class);

    private static final Pattern EVENT_TYPE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    @Override
    public void record(
            String eventType,
            Subject subject,
            Object before,
            Object after,
            ScopeContext scope,
            String reason,
            UUID witnessUserId) {
        if (eventType == null || !EVENT_TYPE_CODE.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "Audit event type must be a catalogue code like HELLO_GREETING_REGISTERED"
                            + " (capitals, digits, underscores; at most 40 characters): " + eventType);
        }
        if (subject == null || subject.type() == null || subject.type().isBlank() || subject.id() == null) {
            throw new IllegalArgumentException("Audit record " + eventType + " needs a subject with a type and an id");
        }
        if (scope == null || !scope.hasActiveScope()) {
            throw new IllegalArgumentException("Audit record " + eventType + " needs a scope with an active entity");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("audit.record(" + eventType + ") was called outside a transaction;"
                    + " call it inside the handler's @Transactional method, after the mutation");
        }

        log.info(
                "AUDIT_STUB eventType={} subjectType={} subjectId={} entityId={} locationId={} correlationId={} reason={} witness={}",
                eventType,
                subject.type(),
                subject.id(),
                scope.entityId(),
                scope.locationId(),
                scope.correlationId(),
                reason,
                witnessUserId);
    }
}
