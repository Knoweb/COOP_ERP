package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuShared;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.create")
public class ActivateSharedSkuHandler implements Handles<ActivateSharedSku, UUID> {

    // 22A section 6: the shared path audits SKU_SHARED and publishes sku.shared.v1; the local
    // path keeps SKU_ACTIVATED and sku.activated.v1.
    private static final String AUDIT_SHARED = "SKU_SHARED";

    private final SkuRepository repository;
    private final FederationCaller federation;
    private final AuditFacade audit;
    private final EventPublisher events;

    ActivateSharedSkuHandler(
            SkuRepository repository, FederationCaller federation, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.federation = federation;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ActivateSharedSku command, ScopeContext scope) {
        federation.require(scope);

        Sku sku = SkuGuards.requireOwned(repository, command.skuId(), scope);

        Map<String, Object> before = sku.auditState();

        sku.activateShared();
        repository.saveAndFlush(sku);

        audit.record(AUDIT_SHARED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope);

        events.publish(new SkuShared(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

        return sku.getId();
    }
}
