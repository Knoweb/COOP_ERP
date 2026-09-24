package lk.coopfed.knoweb.m1party.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.m1party.api.BulkRegisterEntities;
import lk.coopfed.knoweb.m1party.api.ValidationReport;
import lk.coopfed.knoweb.m1party.web.generated.BulkApi;
import lk.coopfed.knoweb.m1party.web.generated.BulkRowProblem;
import lk.coopfed.knoweb.m1party.web.generated.BulkRowResult;
import lk.coopfed.knoweb.m1party.web.generated.BulkValidationReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code POST /v1/party/bulk-register} (M1-11): the file to the handler, the report to the caller. */
@RestController
class BulkController implements BulkApi {

    private final Handles<BulkRegisterEntities, ValidationReport> bulkRegister;
    private final CurrentScope currentScope;

    BulkController(Handles<BulkRegisterEntities, ValidationReport> bulkRegister, CurrentScope currentScope) {
        this.bulkRegister = bulkRegister;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<BulkValidationReport> bulkRegister(String idempotencyKey, MultipartFile file) {
        String csv;
        try {
            csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ValidationReport report = bulkRegister.handle(new BulkRegisterEntities(csv), currentScope.get());

        return ResponseEntity.ok(toResponse(report));
    }

    private static BulkValidationReport toResponse(ValidationReport report) {
        return new BulkValidationReport(
                BulkValidationReport.StatusEnum.fromValue(report.status()),
                report.rows(),
                report.registered(),
                report.rejected(),
                report.results().stream().map(BulkController::toResponse).toList());
    }

    private static BulkRowResult toResponse(ValidationReport.RowResult row) {
        BulkRowResult result = new BulkRowResult(
                row.line(),
                row.entityCode(),
                BulkRowResult.StatusEnum.fromValue(row.status()),
                row.problems().stream()
                        .map(problem -> new BulkRowProblem(problem.field(), problem.code()))
                        .toList());
        result.setEntityId(row.entityId());
        return result;
    }
}
