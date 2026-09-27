package lk.coopfed.knoweb.m3pricing.internal.list;

import java.util.LinkedHashMap;
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
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.PriceListDrafted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CreatePriceList (23A section 7). Guards: the owner in an entity-wide OWN scope; the kind; a
 * name. Only TRADE is accepted today: a RETAIL list must pass the ceiling checks of M3-06 (the
 * lowest in-stock MRP from M5, the control price) before it may exist, and an ADVISORY list is
 * the Federation's with nothing to resolve yet; both are deferred for the demo. Mutation: version
 * 1, DRAFT, its own root.
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class CreatePriceListHandler implements Handles<CreatePriceList, UUID> {

    static final String AUDIT_DRAFTED = "PRICELIST_DRAFTED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreatePriceListHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreatePriceList command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        if (!PriceListStore.TRADE.equals(command.kind())) {
            throw new ProblemException(
                    "m3.price_list.kind_not_available", Map.of("kind", String.valueOf(command.kind())));
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "name"));
        }
        String name = command.name().strip();

        UUID priceListId = Ids.next();
        jdbc.update(
                "insert into pricing.price_list (price_list_id, owner_entity_id, kind, name, version,"
                        + " root_price_list_id, status) values (?, ?, ?, ?, 1, ?, 'DRAFT')",
                priceListId,
                scope.entityId(),
                command.kind(),
                name,
                priceListId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priceListId", priceListId);
        after.put("kind", command.kind());
        after.put("name", name);
        after.put("version", 1);
        after.put("status", PriceListStore.DRAFT);
        audit.record(AUDIT_DRAFTED, Subject.of("price_list", priceListId), null, after, scope);

        events.publish(new PriceListDrafted(priceListId, priceListId, scope.entityId(), command.kind(), 1));
        return priceListId;
    }
}
