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
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.PriceListDrafted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CreatePriceList (23A section 7). Guards: the owner in an entity-wide OWN scope; the kind: TRADE
 * for any entity; RETAIL for a society (MPCS) that has no RETAIL list yet (one per MPCS; its later
 * prices are new versions, and the one_draft_retail and one_published_retail indexes are the
 * backstop); ADVISORY for the Federation only; a name. Mutation: version 1, DRAFT, its own root.
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class CreatePriceListHandler implements Handles<CreatePriceList, UUID> {

    static final String AUDIT_DRAFTED = "PRICELIST_DRAFTED";

    private static final String MPCS = "MPCS";

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final PartyQueries party;
    private final Federation federation;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreatePriceListHandler(
            JdbcTemplate jdbc,
            PriceListStore store,
            PartyQueries party,
            Federation federation,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.party = party;
        this.federation = federation;
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
        String kind = command.kind();
        if (PriceListStore.RETAIL.equals(kind)) {
            // doc 23 section 3.1: "A RETAIL list belongs to an MPCS and is the single retail list
            // for all its shops" (G-01, G-02: no per-shop deviation). Its next prices are a new
            // version of the same list (DraftNewVersion), never a second list.
            boolean society = party.getEntity(scope.entityId(), scope)
                    .map(entity -> MPCS.equals(entity.entityType()))
                    .orElse(false);
            if (!society) {
                throw new ProblemException("m3.price_list.retail_mpcs_only");
            }
            if (store.rootOfKind(PriceListStore.RETAIL, scope.entityId()).isPresent()) {
                throw new ProblemException("m3.price_list.retail_exists");
            }
        } else if (PriceListStore.ADVISORY.equals(kind)) {
            // 23A section 7: "ADVISORY/TRADE-federation: caller F".
            if (!federation.isCaller(scope)) {
                throw new ProblemException("m3.price_list.advisory_federation_only");
            }
        } else if (!PriceListStore.TRADE.equals(kind)) {
            throw new ProblemException("m3.price_list.kind_not_available", Map.of("kind", String.valueOf(kind)));
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
