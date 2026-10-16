package lk.coopfed.knoweb.m2catalogue.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BulkImportSkus;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import lk.coopfed.knoweb.m2catalogue.api.ResolveDuplicateSuspect;
import lk.coopfed.knoweb.m2catalogue.internal.steward.BulkImportCsvParser;
import lk.coopfed.knoweb.m2catalogue.internal.steward.BulkImportSkusHandler;
import lk.coopfed.knoweb.m2catalogue.internal.steward.ResolveDuplicateSuspectHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.DuplicateSuspectView;
import lk.coopfed.knoweb.m2catalogue.web.generated.BulkImportSkusRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.DuplicateSuspectResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImportDuplicateSuspectItem;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImportReportResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImportSkuRowItem;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImportValidationError;
import lk.coopfed.knoweb.m2catalogue.web.generated.ResolveDuplicateSuspectRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.StewardApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for catalogue steward operations: bulk SKU import and duplicate suspect resolution
 * (CAT-01, 22A section 5, section 6.2).
 */
@RestController
class StewardController implements StewardApi {

    private final BulkImportSkusHandler bulkImportHandler;
    private final ResolveDuplicateSuspectHandler resolveSuspectHandler;
    private final BulkImportCsvParser csvParser;
    private final CatalogueQueries queries;
    private final CurrentScope currentScope;

    StewardController(
            BulkImportSkusHandler bulkImportHandler,
            ResolveDuplicateSuspectHandler resolveSuspectHandler,
            BulkImportCsvParser csvParser,
            CatalogueQueries queries,
            CurrentScope currentScope) {
        this.bulkImportHandler = bulkImportHandler;
        this.resolveSuspectHandler = resolveSuspectHandler;
        this.csvParser = csvParser;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<ImportReportResponse> importSkus(String idempotencyKey, BulkImportSkusRequest request) {
        ScopeContext scope = currentScope.get();
        if (request == null || request.getMode() == null) {
            throw new ProblemException("request.invalid");
        }

        List<ImportSkuRow> rows;
        if (request.getCsvContent() != null && !request.getCsvContent().isBlank()) {
            try {
                rows = csvParser.parse(request.getCsvContent());
            } catch (IOException e) {
                throw new ProblemException("m2.sku.csv_parse_failed");
            }
        } else if (request.getRows() != null) {
            rows = new ArrayList<>();
            for (ImportSkuRowItem item : request.getRows()) {
                rows.add(new ImportSkuRow(
                        item.getRowNumber() != null ? item.getRowNumber() : rows.size() + 1,
                        item.getSkuCode(),
                        item.getNameEn(),
                        item.getNameSi(),
                        item.getNameTa(),
                        item.getDescriptionEn(),
                        item.getDescriptionSi(),
                        item.getDescriptionTa(),
                        item.getBaseUomCode(),
                        item.getSoldByWeight(),
                        item.getBatchTracked(),
                        item.getExpiryTracked(),
                        item.getHasPrintedMrp(),
                        item.getExpiryWarningDays() != null
                                ? item.getExpiryWarningDays().shortValue()
                                : null,
                        item.getTaxCategoryCode(),
                        item.getPrice(),
                        item.getBarcodes(),
                        item.getBrand(),
                        item.getTags(),
                        null));
            }
        } else {
            rows = List.of();
        }

        BulkImportSkus command = new BulkImportSkus(request.getMode().getValue(), rows);
        ImportReport report;
        try {
            report = bulkImportHandler.handle(command, scope);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(StewardController.class)
                    .error("Bulk import failed in handler: {}", t.getMessage(), t);
            System.err.println("=== BULK IMPORT HANDLER EXCEPTION ===");
            t.printStackTrace(System.err);
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.err.println("ROOT CAUSE: " + root.getClass().getName() + " -> " + root.getMessage());
            if (t instanceof ProblemException pe) {
                throw pe;
            }
            throw new RuntimeException("Bulk import failed: " + root.getMessage(), t);
        }

        ImportReportResponse response = new ImportReportResponse();
        response.setStatus(ImportReportResponse.StatusEnum.fromValue(report.status()));
        response.setRows(report.rows());
        response.setAccepted(report.accepted());
        response.setRejected(report.rejected());

        List<ImportValidationError> errorResponses = new ArrayList<>();
        for (ImportReport.ValidationError err : report.errors()) {
            ImportValidationError errItem = new ImportValidationError();
            errItem.setRowNumber(err.rowNumber());
            errItem.setField(err.field());
            errItem.setCode(err.code());
            errItem.setMessage(err.message());
            errItem.setParams(err.params());
            errorResponses.add(errItem);
        }
        response.setErrors(errorResponses);

        List<ImportDuplicateSuspectItem> suspectResponses = new ArrayList<>();
        for (ImportReport.DuplicateSuspectItem sus : report.suspects()) {
            ImportDuplicateSuspectItem susItem = new ImportDuplicateSuspectItem();
            susItem.setRowNumber(sus.rowNumber());
            susItem.setCandidateName(sus.candidateName());
            susItem.setMatchedSkuId(sus.matchedSkuId());
            susItem.setMatchedSkuCode(sus.matchedSkuCode());
            susItem.setMatchedSkuName(sus.matchedSkuName());
            susItem.setScore(sus.score());
            susItem.setReasons(sus.reasons());
            suspectResponses.add(susItem);
        }
        response.setSuspects(suspectResponses);

        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<DuplicateSuspectResponse>> listDuplicateSuspects(String status, Integer limit) {
        ScopeContext scope = currentScope.get();
        List<DuplicateSuspectView> views = queries.listDuplicateSuspects(status, limit != null ? limit : 50, scope);

        List<DuplicateSuspectResponse> list = new ArrayList<>();
        for (DuplicateSuspectView v : views) {
            DuplicateSuspectResponse item = new DuplicateSuspectResponse();
            item.setSuspectId(v.suspectId());
            item.setSkuA(v.skuA());
            item.setSkuB(v.skuB());
            item.setSkuACode(v.skuACode());
            item.setSkuAName(v.skuAName());
            item.setSkuBCode(v.skuBCode());
            item.setSkuBName(v.skuBName());
            item.setScore(v.score());
            item.setReasons(v.reasons());
            if (v.status() != null) {
                item.setStatus(DuplicateSuspectResponse.StatusEnum.fromValue(v.status()));
            }
            item.setDecidedBy(v.decidedBy());
            item.setDecidedAt(v.decidedAt());
            item.setCreatedAt(v.createdAt());
            list.add(item);
        }

        return ResponseEntity.ok(list);
    }

    @Override
    public ResponseEntity<Void> resolveDuplicateSuspect(
            UUID suspectId, String idempotencyKey, ResolveDuplicateSuspectRequest request) {
        ScopeContext scope = currentScope.get();
        if (request == null || request.getDecision() == null) {
            throw new ProblemException("request.invalid");
        }

        ResolveDuplicateSuspect command =
                new ResolveDuplicateSuspect(suspectId, request.getDecision().getValue(), request.getCanonicalSkuId());
        resolveSuspectHandler.handle(command, scope);

        return ResponseEntity.noContent().build();
    }
}
