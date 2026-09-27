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
import lk.coopfed.knoweb.m3pricing.api.DraftNewVersion;
import lk.coopfed.knoweb.m3pricing.api.PriceListDrafted;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DraftNewVersion (23A section 7). Guards: the owner in an entity-wide OWN scope; the source
 * exists in the caller's own lists (row-level security hides the others) and is PUBLISHED or
 * SUPERSEDED; the list has no other draft (the one_draft_per_list index is the backstop).
 * Mutation: the next version as a DRAFT, with no lines: carrying the source's lines forward is
 * deferred for the demo (M3-04).
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class DraftNewVersionHandler implements Handles<DraftNewVersion, UUID> {

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuditFacade audit;
    private final EventPublisher events;

    DraftNewVersionHandler(JdbcTemplate jdbc, PriceListStore store, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DraftNewVersion command, ScopeContext scope) {
        if (command == null || command.sourcePriceListId() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        PriceListView source = store.find(command.sourcePriceListId())
                .filter(list -> list.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.price_list.not_found"));
        if (!PriceListStore.PUBLISHED.equals(source.status()) && !PriceListStore.SUPERSEDED.equals(source.status())) {
            throw new ProblemException("m3.price_list.not_published");
        }
        if (store.draftExists(source.rootPriceListId())) {
            throw new ProblemException("m3.price_list.draft_exists");
        }

        UUID priceListId = Ids.next();
        int version = store.latestVersion(source.rootPriceListId()) + 1;
        jdbc.update(
                "insert into pricing.price_list (price_list_id, owner_entity_id, kind, name, version,"
                        + " root_price_list_id, source_version_id, status) values (?, ?, ?, ?, ?, ?, ?, 'DRAFT')",
                priceListId,
                scope.entityId(),
                source.kind(),
                source.name(),
                version,
                source.rootPriceListId(),
                source.priceListId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priceListId", priceListId);
        after.put("rootPriceListId", source.rootPriceListId());
        after.put("sourceVersionId", source.priceListId());
        after.put("version", version);
        after.put("status", PriceListStore.DRAFT);
        audit.record(CreatePriceListHandler.AUDIT_DRAFTED, Subject.of("price_list", priceListId), null, after, scope);

        events.publish(
                new PriceListDrafted(priceListId, source.rootPriceListId(), scope.entityId(), source.kind(), version));
        return priceListId;
    }
}
