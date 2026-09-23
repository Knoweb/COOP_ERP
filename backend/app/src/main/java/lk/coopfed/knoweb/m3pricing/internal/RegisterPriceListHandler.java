package lk.coopfed.knoweb.m3pricing.internal;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.PriceListRegistered;
import lk.coopfed.knoweb.m3pricing.api.RegisterPriceList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shape of every command handler in the system (17A section 12; AGENTS.md):
 * guards, then the mutation, then audit, then the event, in one transaction, in that order,
 * and nothing else. If any step throws, the whole transaction rolls back: there is never a
 * row without its audit record, or an event for a row that was not saved.
 *
 * <p>The handler does not check the permission, the idempotency key or the tenant:
 * the kernel does the first two from the annotation and the request, and row-level
 * security does the third.
 */
@Service
@CommandHandler(permission = "prc.price_list.register")
class RegisterPriceListHandler implements Handles<RegisterPriceList, UUID> {

    /** Audit event types are catalogue codes (doc 19 section 4); 19A validates them. */
    static final String AUDIT_REGISTERED = "PRICING_PRICE_LIST_REGISTERED";

    private final PriceListRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterPriceListHandler(PriceListRepository repository, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterPriceList command, ScopeContext scope) {
        // 1. guards: each throws a ProblemException whose id is a message id in i18n/*.json
        if (!scope.hasActiveScope()) {
            throw new ProblemException("scope.required");
        }
        if (command.textEn() == null || command.textEn().isBlank()) {
            throw new ProblemException("pricing.price_list.text_required");
        }
        // Row-level security limits this to the caller's entity; the unique constraint on
        // (owner_entity_id, text_en) is the safety net when two requests race.
        if (repository.existsByTextEn(command.textEn().strip())) {
            throw new ProblemException(
                    "pricing.price_list.duplicate",
                    Map.of("textEn", command.textEn().strip()));
        }

        // 2. mutation
        PriceList priceList =
                PriceList.create(Ids.next(), scope.entityId(), command.textEn(), command.textSi(), command.textTa());
        repository.save(priceList);

        // 3. audit, in the same transaction
        audit.record(AUDIT_REGISTERED, Subject.of("price_list", priceList.getId()), null, priceList.snapshot(), scope);

        // 4. event, in the same transaction (the outbox)
        events.publish(new PriceListRegistered(priceList.getId(), scope.entityId()));

        return priceList.getId();
    }
}
