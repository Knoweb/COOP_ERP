package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuActivated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.create_local")
public class ActivateLocalSkuHandler implements Handles<ActivateLocalSku, UUID> {

    private static final String AUDIT_ACTIVATED = "SKU_ACTIVATED";

    private final SkuRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    ActivateLocalSkuHandler(SkuRepository repository, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ActivateLocalSku command, ScopeContext scope) {
        Sku sku = SkuGuards.requireOwned(repository, command.skuId(), scope);

        Map<String, Object> before = sku.auditState();

        sku.activateLocal();
        repository.saveAndFlush(sku);

        audit.record(AUDIT_ACTIVATED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope);

        events.publish(new SkuActivated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

        return sku.getId();
    }
}
