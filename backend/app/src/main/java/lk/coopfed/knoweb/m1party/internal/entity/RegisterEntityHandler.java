package lk.coopfed.knoweb.m1party.internal.entity;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.EntityRegistered;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "gov.entity.register")
class RegisterEntityHandler implements Handles<RegisterEntity, UUID> {

    static final String AUDIT_REGISTERED = "ENTITY_REGISTERED";

    private final EntityRepository repository;
    private final EntityRegistrar registrar;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterEntityHandler(EntityRepository repository, AuditFacade audit, EventPublisher events) {

        this.repository = repository;
        this.registrar = new EntityRegistrar(repository);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterEntity command, ScopeContext scope) {

        // 1. caller must be the Federation in an entity-wide OWN scope.
        FederationCaller.require(scope, repository);

        // 2 to 4. the rules and the entity they produce (EntityRegistrar).
        Entity entity = registrar.prepare(command);

        // 5. mutation.
        try {
            /*
             * Flush here so a global duplicate entity_code is translated before
             * audit/event publication. The transaction still rolls back as one unit.
             */
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw EntityRegistrar.translate(ex, entity);
        }

        // 6. audit.
        audit.record(AUDIT_REGISTERED, Subject.of("entity", entity.getId()), null, entity.auditState(), scope);

        // 7. committed-fact event/outbox.
        events.publish(new EntityRegistered(entity.getId(), entity.entityType()));

        return entity.getId();
    }
}
