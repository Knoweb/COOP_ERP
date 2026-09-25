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
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.create_local")
public class UpdateLocalSkuHandler implements Handles<UpdateSku, UUID> {

    private static final String AUDIT_UPDATED = "SKU_UPDATED";

    private final SkuRepository repository;
    private final SkuRules rules;
    private final InventoryLotQuery inventory;
    private final AuditFacade audit;
    private final EventPublisher events;

    UpdateLocalSkuHandler(
            SkuRepository repository,
            SkuRules rules,
            InventoryLotQuery inventory,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.rules = rules;
        this.inventory = inventory;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(UpdateSku command, ScopeContext scope) {
        return update(command, scope);
    }

    private UUID update(UpdateSku command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        SkuDetails details = command.details();
        rules.validate(details);

        Sku sku = SkuGuards.requireOwned(repository, command.skuId(), scope);

        // A SHARED SKU is edited on the shared path only (UpdateSharedSkuHandler and its
        // Federation guard); the router sends it there, and this holds for any other caller.
        if (Sku.SHARED.equals(sku.status())) {
            throw new ProblemException(FederationCaller.FEDERATION_ONLY);
        }

        validateSharedTranslations(sku, details);
        validateInventoryIdentityChange(sku, details);

        Map<String, Object> before = sku.auditState();

        sku.update(details);
        repository.saveAndFlush(sku);

        audit.record(AUDIT_UPDATED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope);

        events.publish(new SkuUpdated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

        return sku.getId();
    }

    private void validateInventoryIdentityChange(Sku sku, SkuDetails details) {

        boolean changed =
                !sku.baseUomCode().equalsIgnoreCase(details.baseUomCode().strip())
                        || sku.batchTracked() != details.batchTracked()
                        || sku.expiryTracked() != details.expiryTracked();

        if (changed && inventory.hasAnyLot(sku.getId())) {
            throw new ProblemException("m2.sku.has_lots", Map.of("skuId", sku.getId()));
        }
    }

    private static void validateSharedTranslations(Sku sku, SkuDetails details) {

        if (Sku.SHARED.equals(sku.status()) && (blank(details.shortNameSi()) || blank(details.shortNameTa()))) {
            throw new ProblemException("m2.sku.shared_translations_required");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
