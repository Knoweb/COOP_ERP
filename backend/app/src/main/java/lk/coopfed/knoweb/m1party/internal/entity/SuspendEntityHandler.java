package lk.coopfed.knoweb.m1party.internal.entity;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.EntitySuspended;
import lk.coopfed.knoweb.m1party.api.SuspendEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "gov.entity.suspend", requiresMfa = true)
class SuspendEntityHandler implements Handles<SuspendEntity, UUID> {

    static final String AUDIT_SUSPENDED = "ENTITY_SUSPENDED";

    private final EntityRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    SuspendEntityHandler(EntityRepository repository, AuditFacade audit, EventPublisher events) {

        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SuspendEntity command, ScopeContext scope) {

        requireScope(scope);

        if (command == null || command.entityId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "entityId"));
        }

        Entity entity = repository
                .findById(command.entityId())
                .orElseThrow(() -> new ProblemException("m1.entity.not_found", Map.of("entityId", command.entityId())));

        if (!entity.isActive()) {
            throw new ProblemException(
                    "m1.entity.not_active", Map.of("entityId", entity.getId(), "status", entity.status()));
        }

        requireReason(command.reasonCode());

        Map<String, Object> before = entity.auditState();

        entity.suspend();
        repository.saveAndFlush(entity);

        Map<String, Object> after = entity.auditState();

        audit.record(
                AUDIT_SUSPENDED,
                Subject.of("entity", entity.getId()),
                before,
                after,
                scope,
                auditReason(command.reasonCode(), command.reasonText()));

        events.publish(new EntitySuspended(entity.getId(), entity.entityCode(), entity.entityType(), entity.status()));

        return entity.getId();
    }

    private static void requireScope(ScopeContext scope) {

        if (scope == null || !scope.hasActiveScope()) {
            throw new ProblemException("scope.required");
        }

        if (scope.locationId() != null) {
            throw new ProblemException("scope.invalid");
        }
    }

    private static void requireReason(String reasonCode) {

        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ProblemException("m1.entity.reason_required");
        }
    }

    private static String auditReason(String reasonCode, String reasonText) {

        String code = reasonCode.strip();

        if (reasonText == null || reasonText.isBlank()) {
            return code;
        }

        return code + ": " + reasonText.strip();
    }
}
