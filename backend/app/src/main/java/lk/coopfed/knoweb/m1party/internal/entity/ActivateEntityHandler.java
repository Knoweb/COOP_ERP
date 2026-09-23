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
import lk.coopfed.knoweb.m1party.api.ActivateEntity;
import lk.coopfed.knoweb.m1party.api.EntityActivated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "gov.entity.activate", requiresMfa = true)
class ActivateEntityHandler implements Handles<ActivateEntity, UUID> {

    static final String AUDIT_ACTIVATED = "ENTITY_ACTIVATED";

    private final EntityRepository repository;
    private final EntityActivationPrerequisites prerequisites;
    private final AuditFacade audit;
    private final EventPublisher events;

    ActivateEntityHandler(
            EntityRepository repository,
            EntityActivationPrerequisites prerequisites,
            AuditFacade audit,
            EventPublisher events) {

        this.repository = repository;
        this.prerequisites = prerequisites;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ActivateEntity command, ScopeContext scope) {

        FederationCaller.require(scope, repository);

        if (command == null || command.entityId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "entityId"));
        }

        Entity entity = repository
                .findById(command.entityId())
                .orElseThrow(() -> new ProblemException("m1.entity.not_found", Map.of("entityId", command.entityId())));

        // Guard 1: status must be ONBOARDING.
        if (!entity.isOnboarding()) {
            throw new ProblemException(
                    "m1.entity.not_onboarding", Map.of("entityId", entity.getId(), "status", entity.status()));
        }

        // Guard 2: responsible officer must already be appointed.
        if (!entity.hasResponsibleOfficer()) {
            throw new ProblemException("m1.entity.responsible_officer_required", Map.of("entityId", entity.getId()));
        }

        // Guard 3: at least one entity-level gov.user.manage holder.
        if (!prerequisites.hasUserManager(entity.getId())) {

            throw new ProblemException("m1.entity.admin_required", Map.of("entityId", entity.getId()));
        }

        // Guard 4: VAT registration must be present.
        if (!entity.hasVatRegistration()) {
            throw new ProblemException("m1.entity.vat_required", Map.of("entityId", entity.getId()));
        }

        Map<String, Object> before = entity.auditState();

        // Mutation.
        entity.activate();
        repository.saveAndFlush(entity);

        Map<String, Object> after = entity.auditState();

        // Audit in the same transaction.
        audit.record(AUDIT_ACTIVATED, Subject.of("entity", entity.getId()), before, after, scope);

        // Event in the same transaction.
        events.publish(new EntityActivated(entity.getId(), entity.entityCode(), entity.entityType(), entity.status()));

        return entity.getId();
    }
}
