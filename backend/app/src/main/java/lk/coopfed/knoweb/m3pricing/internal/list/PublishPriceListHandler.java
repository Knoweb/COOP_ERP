package lk.coopfed.knoweb.m3pricing.internal.list;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.PriceListPublished;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PublishPriceList (23A section 7; doc 23 section 4.1). Guards: the owner in an entity-wide OWN
 * scope; the list is one of the caller's and a DRAFT; apply_from is today or later; it has lines;
 * every line is still valid (the validator runs again: a SKU may have been deactivated since);
 * TRADE: a fresh second factor (requiresMfa; only TRADE lists exist so far). Mutation: the lines
 * are dated apply_from, the draft becomes PUBLISHED and the previous published version
 * SUPERSEDED. Event: price_list.published.v1.
 *
 * <p>Deferred for the demo (M3-04): closing the previous version's lines at apply_from - 1 and
 * carrying unchanged lines forward. The trade price lookup reads the newest version in force on
 * the date (PriceListStore.versionInForce), so the previous version stops answering at the new
 * one's apply_from without its lines being closed. The previous version is marked SUPERSEDED at
 * publication rather than at apply_from; it still answers for dates before apply_from.
 */
@Service
@CommandHandler(permission = "prc.pricelist.publish", requiresMfa = true)
public class PublishPriceListHandler implements Handles<PublishPriceList, UUID> {

    static final String AUDIT_PUBLISHED = "PRICELIST_PUBLISHED";

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuthoringValidator validator;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    PublishPriceListHandler(
            JdbcTemplate jdbc,
            PriceListStore store,
            AuthoringValidator validator,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.validator = validator;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public UUID handle(PublishPriceList command, ScopeContext scope) {
        if (command == null || command.priceListId() == null || command.applyFrom() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        PriceListView list = store.find(command.priceListId())
                .filter(found -> found.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.price_list.not_found"));
        if (!PriceListStore.DRAFT.equals(list.status())) {
            throw new ProblemException("m3.price_list.not_draft");
        }
        LocalDate today = PriceListRules.today(clock, businessZone);
        if (command.applyFrom().isBefore(today)) {
            throw new ProblemException("m3.price_list.apply_from_past");
        }
        List<PriceListLineView> lines = store.lines(list.priceListId());
        if (lines.isEmpty()) {
            throw new ProblemException("m3.price_list.no_lines");
        }
        List<SetLines.Line> asAuthored = lines.stream()
                .map(line -> new SetLines.Line(line.skuId(), line.uomCode(), line.tierFromQty(), line.price()))
                .toList();
        if (validator.check(asAuthored, scope).stream().anyMatch(outcome -> !outcome.ok())) {
            throw new ProblemException("m3.price_list.lines_invalid");
        }

        Optional<PriceListView> previous = store.published(list.rootPriceListId());
        jdbc.update(
                "update pricing.price_list_line set effective_from = ? where price_list_id = ?",
                Date.valueOf(command.applyFrom()),
                list.priceListId());
        previous.ifPresent(old -> jdbc.update(
                "update pricing.price_list set status = 'SUPERSEDED' where price_list_id = ?", old.priceListId()));
        jdbc.update(
                "update pricing.price_list set status = 'PUBLISHED', apply_from = ?, published_by = ?,"
                        + " published_at = ? where price_list_id = ?",
                Date.valueOf(command.applyFrom()),
                scope.userId(),
                Timestamp.from(clock.instant()),
                list.priceListId());

        List<UUID> skuIds =
                lines.stream().map(PriceListLineView::skuId).distinct().toList();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", PriceListStore.DRAFT);
        before.put("supersedes", previous.map(PriceListView::priceListId).orElse(null));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", PriceListStore.PUBLISHED);
        after.put("applyFrom", command.applyFrom());
        after.put("version", list.version());
        after.put("lines", lines.size());
        audit.record(AUDIT_PUBLISHED, Subject.of("price_list", list.priceListId()), before, after, scope);

        events.publish(new PriceListPublished(
                list.priceListId(),
                list.rootPriceListId(),
                list.kind(),
                scope.entityId(),
                list.version(),
                command.applyFrom(),
                skuIds));
        return list.priceListId();
    }
}
