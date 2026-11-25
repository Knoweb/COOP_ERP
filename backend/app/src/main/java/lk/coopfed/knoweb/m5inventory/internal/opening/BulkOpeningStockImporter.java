package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.api.BulkImportOpeningStock;
import lk.coopfed.knoweb.m5inventory.api.BulkOpeningStockReport;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceChanged;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceLine;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockBulkImported;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockPreviewRow;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockProblem;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk loader that imports opening stock from an Excel (.xlsx) file, CSV spreadsheet, or row list (CAT-11, PGL-M2-06).
 *
 * <p>Supports {@code VALIDATE} mode for row-level diagnostics and preview without database mutation,
 * and {@code COMMIT} mode which creates an immutable opening balance document in unconfirmed {@code DRAFT}
 * state, awaiting entity and Federation signatures.
 */
@Service
@CommandHandler(permission = "inv.opening.prepare")
public class BulkOpeningStockImporter implements Handles<BulkImportOpeningStock, BulkOpeningStockReport> {

    public static final String AUDIT_STOCK_BULK_IMPORTED = "STOCK_BULK_IMPORTED";

    private final OpeningBalanceStore store;
    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final BatchQueries batches;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public BulkOpeningStockImporter(
            OpeningBalanceStore store,
            PartyQueries party,
            CatalogueQueries catalogue,
            BatchQueries batches,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.store = store;
        this.party = party;
        this.catalogue = catalogue;
        this.batches = batches;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkOpeningStockReport handle(BulkImportOpeningStock command, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }

        UUID location = command.locationId();
        Optional<LocationView> locationView = location == null
                ? Optional.empty()
                : party.getLocation(location, scope)
                        .filter(l -> scope.entityId().equals(l.ownerEntityId()));
        if (locationView.isEmpty()) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", String.valueOf(location)));
        }
        LocationView ownedLocation = locationView.orElseThrow();
        if (!"ONBOARDING".equals(ownedLocation.status())) {
            throw new ProblemException(
                    "m5.opening.location_not_onboarding",
                    Map.of("locationId", location, "status", ownedLocation.status()));
        }
        if (store.locationHasMovements(location)) {
            throw new ProblemException("m5.opening.location_has_stock", Map.of("locationId", location));
        }
        if (store.openBalanceAt(location)) {
            throw new ProblemException("m5.opening.already_open", Map.of("locationId", location));
        }

        List<OpeningStockRow> rows = command.rows();
        if (command.sheet() != null && !command.sheet().isBlank()) {
            rows = BulkOpeningStockExcelParser.parse(command.sheet());
        }
        if (rows == null || rows.isEmpty()) {
            throw new ProblemException("m5.opening.lines_required");
        }

        boolean isValidateOnly = BulkImportOpeningStock.MODE_VALIDATE.equalsIgnoreCase(command.mode());

        List<OpeningBalanceLine> lines = new ArrayList<>();
        List<BatchView> knownBatches = new ArrayList<>();
        List<UUID> resolvedSkuIds = new ArrayList<>();
        List<UUID> resolvedBatchIds = new ArrayList<>();
        List<OpeningStockProblem> problems = new ArrayList<>();
        List<OpeningStockPreviewRow> previewRows = new ArrayList<>();
        BigDecimal totalValuation = BigDecimal.ZERO;
        int validRows = 0;
        int errorRows = 0;

        for (int i = 0; i < rows.size(); i++) {
            int lineNo = i + 1;
            OpeningStockRow row = rows.get(i);
            String rowError = null;

            UUID batchId = row.batchId();
            UUID skuId = row.skuId();
            BatchView batch = null;
            SkuView skuView = null;

            if (row == null) {
                rowError = "m5.opening.line_invalid";
                problems.add(new OpeningStockProblem(lineNo, null, "row", rowError));
                if (!isValidateOnly) {
                    throw new ProblemException(rowError);
                }
                errorRows++;
                previewRows.add(errorPreviewRow(lineNo, null, rowError));
                continue;
            }

            // 1. Validate Quantity
            if (row.qty() == null
                    || row.qty().signum() <= 0
                    || row.qty().stripTrailingZeros().scale() > 3) {
                rowError = "m5.opening.line_invalid";
                problems.add(new OpeningStockProblem(lineNo, row.sku(), "qty", rowError));
                if (!isValidateOnly) {
                    throw new ProblemException(rowError);
                }
            }

            // 2. Validate Unit Cost
            if (row.unitCost() == null
                    || row.unitCost().signum() < 0
                    || row.unitCost().stripTrailingZeros().scale() > 4) {
                rowError = "m5.opening.line_invalid";
                problems.add(new OpeningStockProblem(lineNo, row.sku(), "unitCost", rowError));
                if (!isValidateOnly) {
                    throw new ProblemException(rowError);
                }
            }

            // 3. Resolve Batch or SKU
            if (batchId != null) {
                Optional<BatchView> foundBatch = batches.getBatch(batchId, scope);
                if (foundBatch.isEmpty()) {
                    rowError = "m5.batch.not_found";
                    problems.add(new OpeningStockProblem(lineNo, row.sku(), "batchId", rowError));
                    if (!isValidateOnly) {
                        throw new ProblemException(rowError, Map.of("batchId", batchId));
                    }
                } else {
                    batch = foundBatch.get();
                    skuId = batch.skuId();
                }
            } else {
                if (skuId == null && row.sku() != null && !row.sku().isBlank()) {
                    skuId = resolveSku(row.sku().strip(), location, scope);
                }
                if (skuId == null) {
                    rowError = "m5.opening.sku_not_found";
                    problems.add(new OpeningStockProblem(lineNo, row.sku(), "sku", rowError));
                    if (!isValidateOnly) {
                        throw new ProblemException(rowError, Map.of("row", lineNo));
                    }
                }
            }

            if (skuId != null && rowError == null) {
                Optional<SkuView> foundSku = catalogue.getSku(skuId, scope);
                if (foundSku.isEmpty()) {
                    rowError = "m5.opening.sku_not_found";
                    problems.add(new OpeningStockProblem(lineNo, row.sku(), "skuId", rowError));
                    if (!isValidateOnly) {
                        throw new ProblemException(rowError, Map.of("skuId", skuId));
                    }
                } else {
                    skuView = foundSku.get();
                }
            }

            // 4. Batch & Expiry Rules against SKU catalog definition
            if (skuView != null && rowError == null) {
                if (skuView.batchTracked() && batch == null) {
                    if (row.batchNo() == null || row.batchNo().isBlank()) {
                        rowError = "m5.opening.batch_required";
                        problems.add(new OpeningStockProblem(lineNo, row.sku(), "batchNo", rowError));
                    } else if (row.batchNo().length() > 40) {
                        rowError = "m5.opening.batch_no_too_long";
                        problems.add(new OpeningStockProblem(lineNo, row.sku(), "batchNo", rowError));
                    }
                }

                if (skuView.expiryTracked() && batch == null) {
                    if (row.expiryDate() == null) {
                        rowError = "m5.opening.expiry_required";
                        problems.add(new OpeningStockProblem(lineNo, row.sku(), "expiryDate", rowError));
                    }
                }

                if (skuView.hasPrintedMrp() && batch == null) {
                    if (row.printedMrp() == null || row.printedMrp().signum() <= 0) {
                        rowError = "m5.opening.mrp_required";
                        problems.add(new OpeningStockProblem(lineNo, row.sku(), "printedMrp", rowError));
                    }
                }

                if (row.uom() != null && !row.uom().isBlank()) {
                    if (!row.uom().equalsIgnoreCase(skuView.baseUomCode())) {
                        rowError = "m5.opening.uom_mismatch";
                        problems.add(new OpeningStockProblem(lineNo, row.sku(), "uom", rowError));
                    }
                }
            }

            if (rowError != null) {
                errorRows++;
                previewRows.add(new OpeningStockPreviewRow(
                        lineNo,
                        row.sku(),
                        skuId,
                        skuView == null ? null : skuView.skuCode(),
                        skuView == null ? null : skuView.nameEn(),
                        skuView == null ? row.uom() : skuView.baseUomCode(),
                        batch == null ? row.batchNo() : batch.batchNo(),
                        batch == null ? row.expiryDate() : batch.expiryDate(),
                        batch == null ? row.printedMrp() : batch.printedMrp(),
                        row.condition() == null ? LotCondition.GOOD : row.condition(),
                        row.qty(),
                        row.unitCost(),
                        null,
                        skuView != null && skuView.batchTracked(),
                        skuView != null && skuView.expiryTracked(),
                        skuView != null && skuView.hasPrintedMrp(),
                        "ERROR",
                        rowError));
                continue;
            }

            // Valid line
            validRows++;
            BigDecimal extendedCost = row.qty().multiply(row.unitCost()).setScale(2, RoundingMode.HALF_UP);
            totalValuation = totalValuation.add(extendedCost);

            resolvedSkuIds.add(skuId);
            resolvedBatchIds.add(batchId);
            knownBatches.add(batch);

            OpeningBalanceLine line = new OpeningBalanceLine(
                    batchId,
                    row.condition() == null ? LotCondition.GOOD : row.condition(),
                    row.qty(),
                    row.unitCost(),
                    skuId,
                    batch == null ? row.batchNo() : batch.batchNo(),
                    batch == null ? row.expiryDate() : batch.expiryDate(),
                    batch == null ? row.printedMrp() : batch.printedMrp());
            lines.add(line);

            previewRows.add(new OpeningStockPreviewRow(
                    lineNo,
                    row.sku(),
                    skuId,
                    skuView.skuCode(),
                    skuView.nameEn(),
                    skuView.baseUomCode(),
                    batch == null ? row.batchNo() : batch.batchNo(),
                    batch == null ? row.expiryDate() : batch.expiryDate(),
                    batch == null ? row.printedMrp() : batch.printedMrp(),
                    line.condition(),
                    row.qty(),
                    row.unitCost(),
                    extendedCost,
                    skuView.batchTracked(),
                    skuView.expiryTracked(),
                    skuView.hasPrintedMrp(),
                    "VALID",
                    null));
        }

        // VALIDATE MODE: Zero DB writes, zero audits, zero events
        if (isValidateOnly) {
            String status = problems.isEmpty() ? BulkOpeningStockReport.VALID : BulkOpeningStockReport.REJECTED;
            return new BulkOpeningStockReport(
                    null, null, status, rows.size(), 0, validRows, errorRows, totalValuation, problems, previewRows);
        }

        // COMMIT MODE: If any problem exists, fail before inserting
        if (!problems.isEmpty()) {
            throw new ProblemException("m5.opening.line_invalid");
        }

        UUID openingBalanceId = Ids.next();
        jdbc.update(
                "insert into inventory.opening_balance (opening_balance_id, owner_entity_id, location_id, prepared_by)"
                        + " values (?, ?, ?, ?)",
                openingBalanceId,
                scope.entityId(),
                location,
                scope.userId());

        for (int i = 0; i < lines.size(); i++) {
            OpeningBalanceLine line = lines.get(i);
            BatchView knownBatch = knownBatches.get(i);
            jdbc.update(
                    """
                    insert into inventory.opening_balance_line
                        (line_id, opening_balance_id, owner_entity_id, location_id, line_no, batch_id, sku_id, condition,
                         qty, unit_cost, batch_no, expiry_date, printed_mrp)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    openingBalanceId,
                    scope.entityId(),
                    location,
                    i + 1,
                    resolvedBatchIds.get(i),
                    resolvedSkuIds.get(i),
                    line.condition() == null ? "GOOD" : line.condition().name(),
                    line.qty(),
                    line.unitCost(),
                    knownBatch == null ? line.batchNo() : knownBatch.batchNo(),
                    knownBatch == null ? line.expiryDate() : knownBatch.expiryDate(),
                    knownBatch == null ? line.printedMrp() : knownBatch.printedMrp());
        }

        UUID importId = Ids.next();

        audit.record(
                PrepareOpeningBalanceHandler.AUDIT_PREPARED,
                Subject.of("opening_balance", openingBalanceId),
                null,
                Map.of("locationId", location, "status", "DRAFT", "lines", lines.size()),
                scope);

        audit.record(
                AUDIT_STOCK_BULK_IMPORTED,
                Subject.of("opening_balance", openingBalanceId),
                null,
                Map.of(
                        "importId",
                        importId,
                        "locationId",
                        location,
                        "totalRows",
                        rows.size(),
                        "importedLines",
                        lines.size(),
                        "totalValuation",
                        totalValuation),
                scope);

        events.publish(new OpeningBalanceChanged(openingBalanceId, scope.entityId(), location, "DRAFT"));
        events.publish(new OpeningStockBulkImported(
                importId, scope.entityId(), location, openingBalanceId, rows.size(), lines.size()));

        return new BulkOpeningStockReport(
                importId,
                openingBalanceId,
                BulkOpeningStockReport.IMPORTED,
                rows.size(),
                lines.size(),
                validRows,
                0,
                totalValuation,
                List.of(),
                previewRows);
    }

    private static OpeningStockPreviewRow errorPreviewRow(int lineNo, String sku, String error) {
        return new OpeningStockPreviewRow(
                lineNo,
                sku,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LotCondition.GOOD,
                null,
                null,
                null,
                false,
                false,
                false,
                "ERROR",
                error);
    }

    private UUID resolveSku(String text, UUID locationId, ScopeContext scope) {
        try {
            UUID asUuid = UUID.fromString(text);
            if (catalogue.getSku(asUuid, scope).isPresent()) {
                return asUuid;
            }
        } catch (IllegalArgumentException ignored) {
        }

        var byBarcode = catalogue.lookupByBarcode(new BarcodeLookup(text, null, null, null, null, locationId), scope);
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
