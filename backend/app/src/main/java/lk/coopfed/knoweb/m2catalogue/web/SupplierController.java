package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BulkImportSuppliers;
import lk.coopfed.knoweb.m2catalogue.api.RegisterSupplier;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportReport;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportRow;
import lk.coopfed.knoweb.m2catalogue.internal.supplier.BulkImportSuppliersHandler;
import lk.coopfed.knoweb.m2catalogue.internal.supplier.RegisterSupplierHandler;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.SupplierView;
import lk.coopfed.knoweb.m2catalogue.web.generated.BulkImportSuppliersRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.RegisterSupplierRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierImportReportResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierImportRowItem;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierValidationError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for supplier operations: registration, listing, and bulk migration (PGL-M2-05).
 */
@RestController
class SupplierController implements SupplierApi {

    private final RegisterSupplierHandler register;
    private final BulkImportSuppliersHandler bulkImport;
    private final BatchQueries queries;
    private final CurrentScope currentScope;

    SupplierController(
            RegisterSupplierHandler register,
            BulkImportSuppliersHandler bulkImport,
            BatchQueries queries,
            CurrentScope currentScope) {
        this.register = register;
        this.bulkImport = bulkImport;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<SupplierResponse>> listSuppliers() {
        ScopeContext scope = currentScope.get();

        return ResponseEntity.ok(queries.listSuppliers(scope).stream()
                .map(SupplierController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<SupplierResponse> registerSupplier(String idempotencyKey, RegisterSupplierRequest request) {
        ScopeContext scope = currentScope.get();

        UUID supplierId = register.handle(new RegisterSupplier(request.getName()), scope);

        SupplierResponse body = queries.listSuppliers(scope).stream()
                .filter(supplier -> supplier.supplierId().equals(supplierId))
                .findFirst()
                .map(SupplierController::toResponse)
                .orElseThrow();

        return ResponseEntity.created(URI.create("/v1/catalogue/suppliers/" + supplierId))
                .body(body);
    }

    @Override
    public ResponseEntity<SupplierImportReportResponse> importSuppliers(
            String idempotencyKey, BulkImportSuppliersRequest request) {
        ScopeContext scope = currentScope.get();
        if (request == null || request.getMode() == null) {
            throw new ProblemException("request.invalid");
        }

        List<SupplierImportRow> rows = new ArrayList<>();
        if (request.getRows() != null) {
            for (SupplierImportRowItem item : request.getRows()) {
                rows.add(new SupplierImportRow(
                        item.getRowNumber() != null ? item.getRowNumber() : (rows.size() + 1),
                        item.getSupplierCode(),
                        item.getName()));
            }
        }

        BulkImportSuppliers command =
                new BulkImportSuppliers(request.getMode().getValue(), request.getCsvContent(), rows);

        SupplierImportReport report = bulkImport.handle(command, scope);

        SupplierImportReportResponse response = new SupplierImportReportResponse();
        response.setStatus(SupplierImportReportResponse.StatusEnum.fromValue(report.status()));
        response.setRows(report.totalRows());
        response.setCreated(report.createdCount());
        response.setSkipped(report.skippedCount());
        response.setRejected(report.rejectedCount());

        List<SupplierValidationError> errList = new ArrayList<>();
        for (SupplierImportReport.SupplierValidationError err : report.errors()) {
            SupplierValidationError errItem = new SupplierValidationError();
            errItem.setRowNumber(err.rowNumber());
            errItem.setField(err.field());
            errItem.setCode(err.code());
            errItem.setMessage(err.message());
            errItem.setRejectedValue(err.rejectedValue());
            errList.add(errItem);
        }
        response.setErrors(errList);

        return ResponseEntity.ok(response);
    }

    private static SupplierResponse toResponse(SupplierView supplier) {
        SupplierResponse response = new SupplierResponse(
                supplier.supplierId(),
                supplier.ownerEntityId(),
                supplier.name(),
                SupplierResponse.StatusEnum.fromValue(supplier.status()));
        response.setSupplierCode(supplier.supplierCode());
        return response;
    }
}
