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
import lk.coopfed.knoweb.m1party.api.AppointResponsibleOfficer;
import lk.coopfed.knoweb.m1party.api.EntityUpdated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "gov.user.manage", requiresMfa = true)
class AppointResponsibleOfficerHandler implements Handles<AppointResponsibleOfficer, UUID> {

    static final String AUDIT_UPDATED = "ENTITY_UPDATED";

    private final EntityRepository repository;
    private final EntityOfficerOwnership ownership;
    private final AuditFacade audit;
    private final EventPublisher events;

    AppointResponsibleOfficerHandler(
            EntityRepository repository, EntityOfficerOwnership ownership, AuditFacade audit, EventPublisher events) {

        this.repository = repository;
        this.ownership = ownership;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AppointResponsibleOfficer command, ScopeContext scope) {

        requireScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        if (command.entityId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "entityId"));
        }

        if (command.userId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "userId"));
        }

        if (command.dataGovernanceSignedOn() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "dataGovernanceSignedOn"));
        }

        Entity entity = repository
                .findById(command.entityId())
                .orElseThrow(() -> new ProblemException("m1.entity.not_found", Map.of("entityId", command.entityId())));

        if (!entity.isOnboarding()) {
            throw new ProblemException(
                    "m1.entity.not_onboarding",
                    Map.of(
                            "entityId", entity.getId(),
                            "status", entity.status()));
        }

        if (!ownership.userBelongsToEntity(command.userId(), entity.getId())) {
            throw new ProblemException(
                    "m1.entity.responsible_officer_invalid",
                    Map.of(
                            "entityId", entity.getId(),
                            "userId", command.userId()));
        }

        Map<String, Object> before = entity.auditState();

        entity.appointResponsibleOfficer(command.userId(), command.dataGovernanceSignedOn());

        repository.saveAndFlush(entity);

        Map<String, Object> after = entity.auditState();

        audit.record(AUDIT_UPDATED, Subject.of("entity", entity.getId()), before, after, scope);

        events.publish(new EntityUpdated(entity.getId(), entity.entityCode(), entity.entityType(), entity.status()));

        return entity.getId();
    }

    private static void requireScope(ScopeContext scope) {

        if (scope == null || !scope.hasActiveScope()) {
            throw new ProblemException("scope.required");
        }

        if (scope.entityId() == null || scope.locationId() != null) {
            throw new ProblemException("scope.invalid");
        }
    }
}
