package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DeactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuDeactivated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.deactivate")
public class DeactivateSkuHandler implements Handles<DeactivateSku, UUID> {

    private static final String AUDIT_DEACTIVATED = "SKU_DEACTIVATED";

    private final SkuRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    DeactivateSkuHandler(SkuRepository repository, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DeactivateSku command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        String reason = SkuGuards.reason(command.reasonCode(), command.reasonText());

        Sku sku = SkuGuards.requireOwned(repository, command.skuId(), scope);

        Map<String, Object> before = sku.auditState();

        sku.deactivate();
        repository.saveAndFlush(sku);

        audit.record(AUDIT_DEACTIVATED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope, reason, null);

        events.publish(new SkuDeactivated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.priorStatus()));

        return sku.getId();
    }
}
