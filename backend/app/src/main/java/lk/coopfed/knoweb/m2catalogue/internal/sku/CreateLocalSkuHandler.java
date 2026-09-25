package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuCreated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.create_local")
public class CreateLocalSkuHandler implements Handles<CreateSku, UUID> {

    private static final String AUDIT_CREATED = "SKU_CREATED";
    private static final int MAX_CODE_ATTEMPTS = 50;

    private final SkuRepository repository;
    private final SkuRules rules;
    private final SkuCodeGenerator codes;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateLocalSkuHandler(
            SkuRepository repository,
            SkuRules rules,
            SkuCodeGenerator codes,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.rules = rules;
        this.codes = codes;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreateSku command, ScopeContext scope) {
        SkuGuards.requireEntityWideScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        rules.validate(command.details());

        String code = nextAvailableCode();

        Sku sku = Sku.create(Ids.next(), code, scope.entityId(), command.details());

        repository.saveAndFlush(sku);

        audit.record(AUDIT_CREATED, Subject.of("sku", sku.getId()), null, sku.auditState(), scope);

        events.publish(new SkuCreated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

        return sku.getId();
    }

    private String nextAvailableCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String candidate = codes.next();
            if (!repository.existsBySkuCode(candidate)) {
                return candidate;
            }
        }

        throw new ProblemException("m2.sku.code_generation_failed");
    }
}
