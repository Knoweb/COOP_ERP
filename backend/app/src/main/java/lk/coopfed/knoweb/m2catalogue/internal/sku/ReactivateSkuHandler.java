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
import lk.coopfed.knoweb.m2catalogue.api.ReactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuReactivated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.deactivate")
public class ReactivateSkuHandler implements Handles<ReactivateSku, UUID> {

    private static final String AUDIT_REACTIVATED = "SKU_REACTIVATED";

    private final SkuRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReactivateSkuHandler(SkuRepository repository, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReactivateSku command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        String reason = SkuGuards.reason(command.reasonCode(), command.reasonText());

        Sku sku = SkuGuards.requireOwned(repository, command.skuId(), scope);

        Map<String, Object> before = sku.auditState();

        sku.reactivate();
        repository.saveAndFlush(sku);

        audit.record(AUDIT_REACTIVATED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope, reason, null);

        events.publish(new SkuReactivated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

        return sku.getId();
    }
}
