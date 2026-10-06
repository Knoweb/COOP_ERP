package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.ApproveTransferRequest;
import lk.coopfed.knoweb.m4trading.api.RejectTransferRequest;
import lk.coopfed.knoweb.m4trading.api.RequestTransfer;
import lk.coopfed.knoweb.m4trading.internal.transfer.ApproveTransferRequestHandler;
import lk.coopfed.knoweb.m4trading.internal.transfer.RejectTransferRequestHandler;
import lk.coopfed.knoweb.m4trading.internal.transfer.RequestTransferHandler;
import lk.coopfed.knoweb.m4trading.query.TransferRequestQueries;
import lk.coopfed.knoweb.m4trading.query.TransferRequestView;
import lk.coopfed.knoweb.m4trading.web.generated.ApproveTransferRequestRequest;
import lk.coopfed.knoweb.m4trading.web.generated.DecisionReasonRequest;
import lk.coopfed.knoweb.m4trading.web.generated.RequestTransferRequest;
import lk.coopfed.knoweb.m4trading.web.generated.TransferRequestApi;
import lk.coopfed.knoweb.m4trading.web.generated.TransferRequestLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.TransferRequestResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The transfer requests of 24A section 5 (M4-10, demo scope): request, approve, reject; reads. */
@RestController
class TransferRequestController implements TransferRequestApi {

    private final RequestTransferHandler request;
    private final ApproveTransferRequestHandler approve;
    private final RejectTransferRequestHandler reject;
    private final TransferRequestQueries queries;
    private final CurrentScope currentScope;

    TransferRequestController(
            RequestTransferHandler request,
            ApproveTransferRequestHandler approve,
            RejectTransferRequestHandler reject,
            TransferRequestQueries queries,
            CurrentScope currentScope) {
        this.request = request;
        this.approve = approve;
        this.reject = reject;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<TransferRequestResponse>> listTransferRequests() {
        return ResponseEntity.ok(queries.listRequests(currentScope.get()).stream()
                .map(TransferRequestController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<TransferRequestResponse> getTransferRequest(UUID requestId) {
        return ResponseEntity.ok(read(requestId, currentScope.get()));
    }

    @Override
    public ResponseEntity<TransferRequestResponse> requestTransfer(String idempotencyKey, RequestTransferRequest body) {
        ScopeContext scope = currentScope.get();
        UUID requestId = request.handle(
                new RequestTransfer(
                        body.getFromLocationId(),
                        body.getToLocationId(),
                        body.getReason(),
                        body.getLines().stream()
                                .map(line -> new RequestTransfer.Line(line.getSkuId(), line.getQty()))
                                .toList()),
                scope);
        return ResponseEntity.created(URI.create("/v1/trading/transfer-requests/" + requestId))
                .body(read(requestId, scope));
    }

    @Override
    public ResponseEntity<TransferRequestResponse> approveTransferRequest(
            String idempotencyKey, UUID requestId, ApproveTransferRequestRequest body) {
        ScopeContext scope = currentScope.get();
        approve.handle(new ApproveTransferRequest(requestId, body == null ? null : body.getFromLocationId()), scope);
        return ResponseEntity.ok(read(requestId, scope));
    }

    @Override
    public ResponseEntity<TransferRequestResponse> rejectTransferRequest(
            String idempotencyKey, UUID requestId, DecisionReasonRequest body) {
        ScopeContext scope = currentScope.get();
        reject.handle(new RejectTransferRequest(requestId, body.getReason()), scope);
        return ResponseEntity.ok(read(requestId, scope));
    }

    private TransferRequestResponse read(UUID requestId, ScopeContext scope) {
        return queries.getRequest(requestId, scope)
                .map(TransferRequestController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.transfer.request_not_found"));
    }

    static TransferRequestResponse toResponse(TransferRequestView view) {
        TransferRequestResponse response = new TransferRequestResponse(
                view.requestId(),
                view.toLocationId(),
                TransferRequestResponse.StatusEnum.fromValue(view.status()),
                view.requestedAt(),
                view.lines().stream()
                        .map(line -> new TransferRequestLineResponse(line.lineId(), line.skuId(), line.qty()))
                        .toList());
        response.setFromLocationId(view.fromLocationId());
        response.setReason(view.reason());
        response.setRequestedBy(view.requestedBy());
        response.setRejectReason(view.rejectReason());
        response.setDecidedBy(view.decidedBy());
        response.setDecidedAt(view.decidedAt());
        response.setTransferId(view.transferId());
        if (view.transferStatus() != null) {
            response.setTransferStatus(TransferRequestResponse.TransferStatusEnum.fromValue(view.transferStatus()));
        }
        return response;
    }
}
