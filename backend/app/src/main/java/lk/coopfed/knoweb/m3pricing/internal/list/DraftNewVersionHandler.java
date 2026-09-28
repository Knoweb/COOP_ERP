package lk.coopfed.knoweb.m3pricing.internal.list;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
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
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DraftNewVersion (23A section 7). Guards: the owner in an entity-wide OWN scope; the source
 * exists in the caller's own lists (row-level security hides the others) and is PUBLISHED or
 * SUPERSEDED; the list has no other draft (the one_draft_per_list and one_draft_retail indexes are
 * the backstop). Mutation: the next version as a DRAFT with the source's lines carried forward
 * (M3-06: "new DRAFT with lines carried forward"), each dated today until publication dates it
 * apply_from, as SetLines dates a draft line. Closing the previous version's lines at
 * apply_from - 1 stays deferred: the lookups read the newest version in force on the date.
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class DraftNewVersionHandler implements Handles<DraftNewVersion, UUID> {

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    DraftNewVersionHandler(
            JdbcTemplate jdbc,
            PriceListStore store,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
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

        LocalDate today = PriceListRules.today(clock, businessZone);
        List<PriceListLineView> carried = store.lines(source.priceListId());
        for (PriceListLineView line : carried) {
            jdbc.update(
                    "insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty,"
                            + " price, effective_from, owner_entity_id) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Ids.next(),
                    priceListId,
                    line.skuId(),
                    line.uomCode(),
                    line.tierFromQty(),
                    line.price(),
                    Date.valueOf(today),
                    scope.entityId());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priceListId", priceListId);
        after.put("rootPriceListId", source.rootPriceListId());
        after.put("sourceVersionId", source.priceListId());
        after.put("version", version);
        after.put("status", PriceListStore.DRAFT);
        after.put("linesCarried", carried.size());
        audit.record(CreatePriceListHandler.AUDIT_DRAFTED, Subject.of("price_list", priceListId), null, after, scope);

        events.publish(
                new PriceListDrafted(priceListId, source.rootPriceListId(), scope.entityId(), source.kind(), version));
        return priceListId;
    }
}
