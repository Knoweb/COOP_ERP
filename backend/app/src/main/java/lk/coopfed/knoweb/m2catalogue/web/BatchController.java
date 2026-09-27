package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.CorrectBatch;
import lk.coopfed.knoweb.m2catalogue.internal.batch.CorrectBatchHandler;
import lk.coopfed.knoweb.m2catalogue.query.BatchFilter;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.web.generated.BatchApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.BatchResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.CorrectBatchRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The batch reads and CorrectBatch (22A section 4, BatchController). RegisterBatch has no operation. */
@RestController
class BatchController implements BatchApi {

    private final CorrectBatchHandler correct;
    private final BatchQueries queries;
    private final CurrentScope currentScope;

    BatchController(CorrectBatchHandler correct, BatchQueries queries, CurrentScope currentScope) {
        this.correct = correct;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<BatchResponse>> listBatches(
            UUID skuId, String batchNo, LocalDate expiringBefore, Integer limit) {
        ScopeContext scope = currentScope.get();

        return ResponseEntity.ok(
                queries.listBatches(new BatchFilter(skuId, batchNo, expiringBefore, limit), scope).stream()
                        .map(BatchController::toResponse)
                        .toList());
    }

    @Override
    public ResponseEntity<BatchResponse> getBatch(UUID batchId) {
        ScopeContext scope = currentScope.get();

        return ResponseEntity.ok(toResponse(found(batchId, scope)));
    }

    @Override
    public ResponseEntity<BatchResponse> correctBatch(
            UUID batchId, String idempotencyKey, CorrectBatchRequest request) {
        ScopeContext scope = currentScope.get();

        UUID replacement = correct.handle(
                new CorrectBatch(
                        batchId,
                        request.getPrintedMrp(),
                        request.getExpiryDate(),
                        request.getReasonCode(),
                        request.getReasonText()),
                scope);

        return ResponseEntity.created(URI.create("/v1/catalogue/batches/" + replacement))
                .body(toResponse(found(replacement, scope)));
    }

    private BatchView found(UUID batchId, ScopeContext scope) {
        return queries.getBatch(batchId, scope)
                .orElseThrow(() -> new ProblemException("m2.batch.not_found", Map.of("batchId", batchId)));
    }

    private static BatchResponse toResponse(BatchView batch) {
        BatchResponse response = new BatchResponse(
                batch.batchId(),
                batch.skuId(),
                batch.batchNo(),
                batch.synthetic(),
                BatchResponse.StatusEnum.fromValue(batch.status()),
                batch.ownerEntityId(),
                batch.createdAt());
        response.setSupplierId(batch.supplierId());
        response.setManufactureDate(batch.manufactureDate());
        response.setExpiryDate(batch.expiryDate());
        response.setPrintedMrp(batch.printedMrp());
        response.setOriginDocumentId(batch.originDocumentId());
        response.setCorrectsBatchId(batch.correctsBatchId());
        return response;
    }
}
