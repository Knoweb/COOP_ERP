package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.web.generated.CaptureGrnRequest;
import lk.coopfed.knoweb.m4trading.web.generated.GrnApi;
import lk.coopfed.knoweb.m4trading.web.generated.GrnLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.GrnResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The GRN operations of 24A section 5 at the web (GrnController): capture, confirm, read. */
@RestController
class GrnController implements GrnApi {

    private final CaptureGrnHandler capture;
    private final ConfirmGrnHandler confirm;
    private final GrnQueries queries;
    private final CurrentScope currentScope;

    GrnController(CaptureGrnHandler capture, ConfirmGrnHandler confirm, GrnQueries queries, CurrentScope currentScope) {
        this.capture = capture;
        this.confirm = confirm;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<GrnResponse> captureGrn(String idempotencyKey, CaptureGrnRequest request) {
        ScopeContext scope = currentScope.get();
        List<CaptureGrn.Line> lines = request.getLines().stream()
                .map(line -> new CaptureGrn.Line(
                        line.getSkuId(),
                        line.getUomCode(),
                        line.getReceivedQty(),
                        line.getDamagedQty(),
                        line.getBatchNo(),
                        line.getManufactureDate(),
                        line.getExpiryDate(),
                        line.getPrintedMrp()))
                .toList();
        UUID grnId = capture.handle(
                new CaptureGrn(request.getDropId(), request.getLocationId(), request.getReceivedOn(), lines), scope);
        return ResponseEntity.created(URI.create("/v1/trading/grns/" + grnId)).body(read(grnId, scope));
    }

    @Override
    public ResponseEntity<GrnResponse> confirmGrn(String idempotencyKey, UUID grnId) {
        ScopeContext scope = currentScope.get();
        confirm.handle(new ConfirmGrn(grnId), scope);
        return ResponseEntity.ok(read(grnId, scope));
    }

    @Override
    public ResponseEntity<GrnResponse> getGrn(UUID grnId) {
        return ResponseEntity.ok(read(grnId, currentScope.get()));
    }

    @Override
    public ResponseEntity<List<GrnResponse>> listGrns(String role) {
        return ResponseEntity.ok(queries.listGrns(OrderQueries.Role.valueOf(role), currentScope.get()).stream()
                .map(GrnController::toResponse)
                .toList());
    }

    private GrnResponse read(UUID grnId, ScopeContext scope) {
        return queries.getGrn(grnId, scope)
                .map(GrnController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.grn.not_found"));
    }

    static GrnResponse toResponse(GrnView grn) {
        GrnResponse response = new GrnResponse(
                grn.grnId(),
                GrnResponse.StatusEnum.fromValue(grn.status()),
                grn.receiverEntityId(),
                grn.receiverLocationId(),
                grn.lines().stream().map(GrnController::toLine).toList());
        response.setDocNumber(grn.docNumberDisplay());
        response.setSellerEntityId(grn.sellerEntityId());
        response.setDropId(grn.dropId());
        response.setDeliveryNoteId(grn.deliveryNoteId());
        response.setReceivedOn(grn.receivedOn());
        response.setConfirmedAt(grn.confirmedAt());
        response.setDiscrepancyId(grn.discrepancyId());
        return response;
    }

    private static GrnLineResponse toLine(GrnView.GrnLineView line) {
        GrnLineResponse response = new GrnLineResponse(
                line.lineId(), line.lineNo(), line.skuId(), line.uomCode(), line.receivedQty(), line.damagedQty());
        response.setExpectedQty(line.expectedQty());
        response.setBatchNo(line.batchNo());
        response.setExpiryDate(line.expiryDate());
        response.setPrintedMrp(line.printedMrp());
        response.setUnitCost(line.unitCost());
        response.setBatchId(line.batchId());
        return response;
    }
}
