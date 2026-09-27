package lk.coopfed.knoweb.m3pricing.internal.list;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.PriceListLinesSet;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult.Outcome;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SetLines (23A section 7). Guards: the owner in an entity-wide OWN scope; the list is one of the
 * caller's and a DRAFT; then the AuthoringValidator, line by line. When any line is refused,
 * nothing is stored and the outcomes say why (23A section 5: "per-line outcomes"); a line flagged
 * for review only is stored. Mutation: the draft's lines are replaced by the given set, each
 * dated today until publication dates it apply_from.
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class SetLinesHandler implements Handles<SetLines, SetLinesResult> {

    static final String AUDIT_LINES_SET = "PRICELIST_LINES_SET";

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuthoringValidator validator;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    SetLinesHandler(
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
    public SetLinesResult handle(SetLines command, ScopeContext scope) {
        if (command == null || command.priceListId() == null || command.lines() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        PriceListView list = store.find(command.priceListId())
                .filter(found -> found.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.price_list.not_found"));
        if (!PriceListStore.DRAFT.equals(list.status())) {
            throw new ProblemException("m3.price_list.not_draft");
        }
        List<Outcome> outcomes = validator.check(command.lines(), scope);
        if (outcomes.stream().anyMatch(outcome -> !outcome.ok())) {
            return new SetLinesResult(false, outcomes);
        }

        LocalDate today = PriceListRules.today(clock, businessZone);
        int before = jdbc.update("delete from pricing.price_list_line where price_list_id = ?", list.priceListId());
        for (SetLines.Line line : command.lines()) {
            jdbc.update(
                    "insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty,"
                            + " price, effective_from, owner_entity_id) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Ids.next(),
                    list.priceListId(),
                    line.skuId(),
                    line.uomCode(),
                    line.tierFromQty(),
                    line.price(),
                    Date.valueOf(today),
                    scope.entityId());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priceListId", list.priceListId());
        after.put("linesReplaced", before);
        after.put("lines", command.lines().size());
        after.put("reviews", outcomes.stream().filter(o -> o.review() != null).count());
        audit.record(AUDIT_LINES_SET, Subject.of("price_list", list.priceListId()), null, after, scope);

        events.publish(new PriceListLinesSet(
                list.priceListId(), scope.entityId(), command.lines().size()));
        return new SetLinesResult(true, outcomes);
    }
}
