package lk.coopfed.knoweb.m3pricing.internal.list;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.ImportPriceListLines;
import lk.coopfed.knoweb.m3pricing.api.PriceListImportReport;
import lk.coopfed.knoweb.m3pricing.api.PriceListImportReport.PriceListImportProblem;
import lk.coopfed.knoweb.m3pricing.api.PriceListLinesImported;
import lk.coopfed.knoweb.m3pricing.api.PriceListLinesSet;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult.Outcome;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk imports price list lines from a sheet or line items into a draft list (CAT-13).
 *
 * <p>Guards: caller has owner scope; price list exists and is in DRAFT state; authoring validator rules.
 * Mutation: populates draft lines within a single @Transactional boundary, audits PRICELIST_LINES_IMPORTED
 * with zero PII, and publishes domain events.
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class ImportPriceListLinesHandler implements Handles<ImportPriceListLines, PriceListImportReport> {

    public static final String AUDIT_LINES_IMPORTED = "PRICELIST_LINES_IMPORTED";

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuthoringValidator validator;
    private final CatalogueQueries catalogue;
    private final PriceListCsvParser parser;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    ImportPriceListLinesHandler(
            JdbcTemplate jdbc,
            PriceListStore store,
            AuthoringValidator validator,
            CatalogueQueries catalogue,
            PriceListCsvParser parser,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jdbc = jdbc;
        this.store = store;
        this.validator = validator;
        this.catalogue = catalogue;
        this.parser = parser;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public PriceListImportReport handle(ImportPriceListLines command, ScopeContext scope) {
        if (command == null || command.priceListId() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);

        PriceListView list = store.findForUpdate(command.priceListId())
                .filter(found -> found.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.price_list.not_found"));
        if (!PriceListStore.DRAFT.equals(list.status())) {
            throw new ProblemException("m3.price_list.not_draft");
        }

        List<PriceListCsvParser.ParsedRow> parsedRows = new ArrayList<>();
        if (command.sheet() != null && !command.sheet().isBlank()) {
            try {
                parsedRows = parser.parse(command.sheet());
            } catch (IOException e) {
                throw new ProblemException("m3.price_list.import.parse_failed");
            }
        } else if (command.lines() != null) {
            int lineNo = 1;
            for (ImportPriceListLines.ImportLineRow row : command.lines()) {
                if (row != null) {
                    parsedRows.add(new PriceListCsvParser.ParsedRow(
                            lineNo++, row.sku(), row.skuId(), row.uomCode(), row.tierFromQty(), row.price(), null));
                }
            }
        }

        if (parsedRows.isEmpty()) {
            List<PriceListImportProblem> emptyProblems =
                    List.of(new PriceListImportProblem(1, null, "sheet", "m3.price_list.import.empty"));
            return new PriceListImportReport(list.priceListId(), false, 0, 0, 1, emptyProblems, List.of());
        }

        int totalRows = parsedRows.size();
        List<PriceListImportProblem> problems = new ArrayList<>();
        List<SetLines.Line> candidateLines = new ArrayList<>();
        Map<String, UUID> skuCache = new HashMap<>();

        for (PriceListCsvParser.ParsedRow row : parsedRows) {
            if (row.parseError() != null) {
                problems.add(new PriceListImportProblem(row.lineNo(), row.rawSku(), "row", row.parseError()));
                continue;
            }

            UUID skuId = row.skuId();
            SkuView skuView = null;
            if (skuId != null) {
                skuView = catalogue.getSku(skuId, scope).orElse(null);
            }
            if (skuView == null && row.rawSku() != null && !row.rawSku().isBlank()) {
                String raw = row.rawSku().trim();
                UUID resolvedId = skuCache.get(raw);
                if (resolvedId == null) {
                    resolvedId = resolveSku(raw, scope);
                    if (resolvedId != null) {
                        skuCache.put(raw, resolvedId);
                    }
                }
                if (resolvedId != null) {
                    skuId = resolvedId;
                    skuView = catalogue.getSku(resolvedId, scope).orElse(null);
                }
            }

            if (skuId == null || skuView == null) {
                problems.add(new PriceListImportProblem(
                        row.lineNo(), row.rawSku(), "sku", "m3.price_list.import.sku_not_found"));
                continue;
            }

            String uom = row.uomCode();
            if (uom == null || uom.isBlank()) {
                uom = skuView.baseUomCode();
            }

            BigDecimal tier = row.tierFromQty() != null ? row.tierFromQty() : BigDecimal.ZERO;
            BigDecimal price = row.price();
            if (price == null || price.signum() < 0) {
                problems.add(new PriceListImportProblem(
                        row.lineNo(), skuView.skuCode(), "price", "m3.price_list.line.price_negative"));
                continue;
            }

            candidateLines.add(new SetLines.Line(skuId, uom, tier, price));
        }

        if (!problems.isEmpty()) {
            return new PriceListImportReport(
                    list.priceListId(), false, totalRows, 0, problems.size(), problems, List.of());
        }

        LocalDate today = PriceListRules.today(clock, businessZone);
        List<Outcome> outcomes = validator.check(list.kind(), candidateLines, today, scope);
        if (outcomes.stream().anyMatch(outcome -> !outcome.ok())) {
            int lineNo = 1;
            for (Outcome outcome : outcomes) {
                if (!outcome.ok()) {
                    problems.add(new PriceListImportProblem(
                            lineNo,
                            outcome.skuId() != null ? outcome.skuId().toString() : null,
                            "line",
                            outcome.reason() != null ? outcome.reason() : "m3.price_list.line.invalid"));
                }
                lineNo++;
            }
            return new PriceListImportReport(
                    list.priceListId(), false, totalRows, 0, problems.size(), problems, outcomes);
        }

        if (command.replace()) {
            jdbc.update("delete from pricing.price_list_line where price_list_id = ?", list.priceListId());
            for (SetLines.Line line : candidateLines) {
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
        } else {
            for (SetLines.Line line : candidateLines) {
                int updated = jdbc.update(
                        "update pricing.price_list_line set price = ?, effective_from = ? where price_list_id = ?"
                                + " and sku_id = ? and uom_code = ? and tier_from_qty = ?",
                        line.price(),
                        Date.valueOf(today),
                        list.priceListId(),
                        line.skuId(),
                        line.uomCode(),
                        line.tierFromQty());
                if (updated == 0) {
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
            }
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priceListId", list.priceListId());
        after.put("lines", candidateLines.size());
        after.put("replaced", command.replace());
        after.put("reviews", outcomes.stream().filter(o -> o.review() != null).count());
        audit.record(AUDIT_LINES_IMPORTED, Subject.of("price_list", list.priceListId()), null, after, scope);

        events.publish(new PriceListLinesImported(
                list.priceListId(), scope.entityId(), candidateLines.size(), command.replace()));
        events.publish(new PriceListLinesSet(list.priceListId(), scope.entityId(), candidateLines.size()));

        return new PriceListImportReport(
                list.priceListId(), true, totalRows, candidateLines.size(), 0, List.of(), outcomes);
    }

    private UUID resolveSku(String text, ScopeContext scope) {
        try {
            UUID asUuid = UUID.fromString(text);
            if (catalogue.getSku(asUuid, scope).isPresent()) {
                return asUuid;
            }
        } catch (IllegalArgumentException ignored) {
        }

        Optional<LookupResult> byBarcode =
                catalogue.lookupByBarcode(new BarcodeLookup(text, null, null, null, null, null), scope);
        if (byBarcode.isPresent()) {
            return byBarcode.get().skuId();
        }

        SkuPage search = catalogue.searchSku(new SkuFilter(null, text, "en", 0, 50), scope);
        if (search != null && search.items() != null) {
            for (SkuView item : search.items()) {
                if (text.equalsIgnoreCase(item.skuCode()) || text.equalsIgnoreCase(item.nameEn())) {
                    return item.skuId();
                }
            }
            if (!search.items().isEmpty()) {
                return search.items().get(0).skuId();
            }
        }

        return null;
    }
}
