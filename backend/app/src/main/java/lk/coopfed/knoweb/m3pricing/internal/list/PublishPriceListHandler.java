package lk.coopfed.knoweb.m3pricing.internal.list;

import java.math.BigDecimal;
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
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.PriceListPublished;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.ReviewRaised;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.internal.review.PricingReviews;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PublishPriceList (23A section 7; doc 23 section 4.1). Guards: the owner in an entity-wide OWN
 * scope; the list is one of the caller's and a DRAFT; apply_from is today or later; it has lines;
 * every line is still valid on apply_from (the validator runs again: a SKU may have been
 * deactivated, a control price entered or a lower-MRP batch received since; for RETAIL and ADVISORY
 * the ceilings in force on apply_from bind); a fresh second factor (requiresMfa: 23A asks it for
 * TRADE, and the permission carries it for every kind, M3-06, decided on the architect's
 * delegation). Mutation: the lines are dated apply_from, the draft becomes PUBLISHED and the
 * previous published version SUPERSEDED. Event: price_list.published.v1.
 *
 * <p>Publication also closes the previous version's line dates at apply_from minus one
 * day, preserving historical pricing. A successor must start strictly after its predecessor.
 * The previous version remains available for historical queries.
 */
@Service
@CommandHandler(permission = "prc.pricelist.publish", requiresMfa = true)
public class PublishPriceListHandler implements Handles<PublishPriceList, UUID> {

    static final String AUDIT_PUBLISHED = "PRICELIST_PUBLISHED";

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuthoringValidator validator;
    private final ConfigRegistry config;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    PublishPriceListHandler(
            JdbcTemplate jdbc,
            PriceListStore store,
            AuthoringValidator validator,
            ConfigRegistry config,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.validator = validator;
        this.config = config;
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
        // Locked, so DRAFT is re-checked after any concurrent publish has committed (M3-05).
        PriceListView list = store.findForUpdate(command.priceListId())
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
        List<SetLinesResult.Outcome> outcomes = validator.check(list.kind(), asAuthored, command.applyFrom(), scope);
        if (outcomes.stream().anyMatch(outcome -> !outcome.ok())) {
            throw new ProblemException("m3.price_list.lines_invalid");
        }

        List<AboveMrp> aboveMrp = PriceListStore.TRADE.equals(list.kind())
                        && config.getBoolean("pricing.trade_above_mrp_review", scope, true)
                ? aboveMrp(asAuthored, outcomes)
                : List.of();

        Optional<PriceListView> previous = store.published(list.rootPriceListId());
        if (previous.isPresent() && !command.applyFrom().isAfter(previous.get().applyFrom())) {
            throw new ProblemException("m3.price_list.apply_from_not_after_previous");
        }
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

        // The successor is now PUBLISHED and its predecessor SUPERSEDED.
        // V0011 permits only this effective_to change on old lines.
        previous.ifPresent(old -> jdbc.update(
                "update pricing.price_list_line set effective_to = ?"
                        + " where price_list_id = ? and effective_to is null",
                Date.valueOf(command.applyFrom().minusDays(1)),
                old.priceListId()));

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
        for (AboveMrp review : aboveMrp) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("priceListId", list.priceListId());
            details.put("uomCode", review.uomCode());
            details.put("tierFromQty", review.tierFromQty());
            details.put("price", review.price());
            details.put("mrp", review.mrp());
            audit.record(PricingReviews.TRADE_ABOVE_MRP, Subject.of("sku", review.skuId()), null, details, scope);
        }

        events.publish(new PriceListPublished(
                list.priceListId(),
                list.rootPriceListId(),
                list.kind(),
                scope.entityId(),
                list.version(),
                command.applyFrom(),
                skuIds));
        for (AboveMrp review : aboveMrp) {
            events.publish(new ReviewRaised(
                    PricingReviews.TRADE_ABOVE_MRP,
                    review.skuId(),
                    scope.entityId(),
                    list.priceListId().toString()));
        }
        return list.priceListId();
    }

    /** A TRADE line above the SKU's lowest printed MRP: the review of doc 23 section 3.4. */
    private record AboveMrp(UUID skuId, String uomCode, BigDecimal tierFromQty, BigDecimal price, BigDecimal mrp) {}

    /**
     * One review per SKU and unit (M3R-06), not one per tier: the highest offending line of the
     * pair, read from the line the outcome belongs to (the validator answers one outcome per line,
     * in the order given).
     */
    private static List<AboveMrp> aboveMrp(List<SetLines.Line> lines, List<SetLinesResult.Outcome> outcomes) {
        Map<String, AboveMrp> highest = new LinkedHashMap<>();
        for (int i = 0; i < outcomes.size() && i < lines.size(); i++) {
            SetLinesResult.Outcome outcome = outcomes.get(i);
            if (!AuthoringValidator.REVIEW_ABOVE_MRP.equals(outcome.review())) {
                continue;
            }
            SetLines.Line line = lines.get(i);
            AboveMrp candidate = new AboveMrp(
                    line.skuId(), line.uomCode(), line.tierFromQty(), line.price(), outcome.ceilingValue());
            highest.merge(
                    line.skuId() + "|" + line.uomCode(),
                    candidate,
                    (kept, next) -> next.price().compareTo(kept.price()) > 0 ? next : kept);
        }
        return List.copyOf(highest.values());
    }
}
