package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.AddClaimPhoto;
import lk.coopfed.knoweb.m4trading.api.ApproveClaim;
import lk.coopfed.knoweb.m4trading.api.DispatchClaimReturn;
import lk.coopfed.knoweb.m4trading.api.RaiseClaim;
import lk.coopfed.knoweb.m4trading.api.RejectClaim;
import lk.coopfed.knoweb.m4trading.internal.claim.AddClaimPhotoHandler;
import lk.coopfed.knoweb.m4trading.internal.claim.ApproveClaimHandler;
import lk.coopfed.knoweb.m4trading.internal.claim.DispatchClaimReturnHandler;
import lk.coopfed.knoweb.m4trading.internal.claim.RaiseClaimHandler;
import lk.coopfed.knoweb.m4trading.internal.claim.RejectClaimHandler;
import lk.coopfed.knoweb.m4trading.query.ClaimQueries;
import lk.coopfed.knoweb.m4trading.query.ClaimView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.web.generated.ApproveClaimRequest;
import lk.coopfed.knoweb.m4trading.web.generated.ClaimApi;
import lk.coopfed.knoweb.m4trading.web.generated.ClaimLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.ClaimPhotoRequest;
import lk.coopfed.knoweb.m4trading.web.generated.ClaimPhotoResponse;
import lk.coopfed.knoweb.m4trading.web.generated.ClaimPhotoStatusResponse;
import lk.coopfed.knoweb.m4trading.web.generated.ClaimResponse;
import lk.coopfed.knoweb.m4trading.web.generated.DecisionReasonRequest;
import lk.coopfed.knoweb.m4trading.web.generated.RaiseClaimRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The claims of 24A section 5 (M4-06, demo scope): raise, photographs, approve, reject, return; reads. */
@RestController
class ClaimController implements ClaimApi {

    private final RaiseClaimHandler raise;
    private final AddClaimPhotoHandler photo;
    private final ApproveClaimHandler approve;
    private final RejectClaimHandler reject;
    private final DispatchClaimReturnHandler dispatchReturn;
    private final ClaimQueries queries;
    private final CurrentScope currentScope;

    @SuppressWarnings("java:S107") // one handler per operation of the slice
    ClaimController(
            RaiseClaimHandler raise,
            AddClaimPhotoHandler photo,
            ApproveClaimHandler approve,
            RejectClaimHandler reject,
            DispatchClaimReturnHandler dispatchReturn,
            ClaimQueries queries,
            CurrentScope currentScope) {
        this.raise = raise;
        this.photo = photo;
        this.approve = approve;
        this.reject = reject;
        this.dispatchReturn = dispatchReturn;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<ClaimResponse>> listClaims(String role) {
        return ResponseEntity.ok(queries.listClaims(OrderQueries.Role.valueOf(role), currentScope.get()).stream()
                .map(ClaimController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<ClaimResponse> getClaim(UUID claimId) {
        return ResponseEntity.ok(read(claimId, currentScope.get()));
    }

    @Override
    public ResponseEntity<ClaimResponse> raiseClaim(String idempotencyKey, RaiseClaimRequest request) {
        ScopeContext scope = currentScope.get();
        UUID claimId = raise.handle(
                new RaiseClaim(
                        request.getGrnId(),
                        request.getKind().getValue(),
                        Boolean.TRUE.equals(request.getReturnRequested()),
                        request.getNote(),
                        request.getLines().stream()
                                .map(line -> new RaiseClaim.Line(line.getGrnLineId(), line.getQty()))
                                .toList()),
                scope);
        return ResponseEntity.created(URI.create("/v1/trading/claims/" + claimId))
                .body(read(claimId, scope));
    }

    @Override
    public ResponseEntity<ClaimPhotoResponse> addClaimPhoto(
            String idempotencyKey, UUID claimId, ClaimPhotoRequest request) {
        Attachments.PresignedUpload upload = photo.handle(
                new AddClaimPhoto(claimId, request.getContentType(), request.getContentLength()), currentScope.get());
        return ResponseEntity.ok(
                new ClaimPhotoResponse(upload.attachmentId(), upload.url().toString(), upload.expiresAt()));
    }

    @Override
    public ResponseEntity<ClaimResponse> approveClaim(
            String idempotencyKey, UUID claimId, ApproveClaimRequest request) {
        ScopeContext scope = currentScope.get();
        List<ApproveClaim.Line> lines = request.getLines() == null
                ? List.of()
                : request.getLines().stream()
                        .map(line -> new ApproveClaim.Line(line.getClaimLineId(), line.getQty()))
                        .toList();
        approve.handle(
                new ApproveClaim(
                        claimId, request.getFindings(), Boolean.TRUE.equals(request.getReturnRequired()), lines),
                scope);
        return ResponseEntity.ok(read(claimId, scope));
    }

    @Override
    public ResponseEntity<ClaimResponse> rejectClaim(
            String idempotencyKey, UUID claimId, DecisionReasonRequest request) {
        ScopeContext scope = currentScope.get();
        reject.handle(new RejectClaim(claimId, request.getReason()), scope);
        return ResponseEntity.ok(read(claimId, scope));
    }

    @Override
    public ResponseEntity<ClaimResponse> dispatchClaimReturn(String idempotencyKey, UUID claimId) {
        ScopeContext scope = currentScope.get();
        dispatchReturn.handle(new DispatchClaimReturn(claimId), scope);
        return ResponseEntity.ok(read(claimId, scope));
    }

    private ClaimResponse read(UUID claimId, ScopeContext scope) {
        return queries.getClaim(claimId, scope)
                .map(ClaimController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.claim.not_found"));
    }

    static ClaimResponse toResponse(ClaimView view) {
        ClaimResponse response = new ClaimResponse(
                view.claimId(),
                ClaimResponse.StatusEnum.fromValue(view.status()),
                ClaimResponse.KindEnum.fromValue(view.kind()),
                view.buyerEntityId(),
                view.sellerEntityId(),
                view.grnId(),
                view.windowEndsAt(),
                view.returnRequested(),
                view.returnRequired(),
                view.photos().stream()
                        .map(p -> new ClaimPhotoStatusResponse(
                                p.attachmentId(), ClaimPhotoStatusResponse.StatusEnum.fromValue(p.status())))
                        .toList(),
                view.lines().stream()
                        .map(line -> {
                            ClaimLineResponse row = new ClaimLineResponse(
                                    line.claimLineId(),
                                    line.grnLineId(),
                                    line.skuId(),
                                    line.uomCode(),
                                    line.claimedQty());
                            row.setBatchId(line.batchId());
                            row.setApprovedQty(line.approvedQty());
                            row.setUnitPrice(line.unitPrice());
                            return row;
                        })
                        .toList());
        response.setDocNumber(view.docNumberDisplay());
        response.setLocationId(view.locationId());
        response.setGrnDocNumber(view.grnDocNumberDisplay());
        response.setRaisedAt(view.raisedAt());
        response.setNote(view.note());
        response.setInvoiceId(view.invoiceId());
        response.setFindings(view.findings());
        response.setRejectReason(view.rejectReason());
        response.setCreditNoteId(view.creditNoteId());
        response.setCreditNoteDocNumber(view.creditNoteDocNumberDisplay());
        response.setDecidedByUserId(view.decidedByUserId());
        response.setDecidedAt(view.decidedAt());
        response.setReturnedAt(view.returnedAt());
        return response;
    }
}
