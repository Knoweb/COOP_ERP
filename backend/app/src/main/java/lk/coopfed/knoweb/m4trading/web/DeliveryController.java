package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.CreateDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.DeliveryLineSummary;
import lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.internal.delivery.CreateDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.delivery.DispatchDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.delivery.IssueDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.DeliveryView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.web.generated.CreateDeliveryNoteRequest;
import lk.coopfed.knoweb.m4trading.web.generated.DeliveryApi;
import lk.coopfed.knoweb.m4trading.web.generated.DeliveryLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.DeliveryNoteResponse;
import lk.coopfed.knoweb.m4trading.web.generated.DispatchRequest;
import lk.coopfed.knoweb.m4trading.web.generated.DropResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The delivery note operations of 24A section 5 (DeliveryController): draft, issue, dispatch, read. */
@RestController
class DeliveryController implements DeliveryApi {

    private final CreateDeliveryNoteHandler create;
    private final IssueDeliveryNoteHandler issue;
    private final DispatchDeliveryNoteHandler dispatch;
    private final DeliveryQueries queries;
    private final CurrentScope currentScope;

    DeliveryController(
            CreateDeliveryNoteHandler create,
            IssueDeliveryNoteHandler issue,
            DispatchDeliveryNoteHandler dispatch,
            DeliveryQueries queries,
            CurrentScope currentScope) {
        this.create = create;
        this.issue = issue;
        this.dispatch = dispatch;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<DeliveryNoteResponse> createDeliveryNote(
            String idempotencyKey, CreateDeliveryNoteRequest request) {
        ScopeContext scope = currentScope.get();
        List<CreateDeliveryNote.Drop> drops = request.getDrops().stream()
                .map(drop -> new CreateDeliveryNote.Drop(
                        drop.getShipToLocationId(),
                        drop.getBillToEntityId(),
                        drop.getLines().stream()
                                .map(line -> new CreateDeliveryNote.Line(
                                        line.getOrderLineId(), line.getQty(), line.getBatchId()))
                                .toList()))
                .toList();
        UUID noteId = create.handle(
                new CreateDeliveryNote(
                        request.getVehicleRef(),
                        request.getDriverName(),
                        request.getRouteRef(),
                        drops,
                        request.getFromLocationId()),
                scope);
        return ResponseEntity.created(URI.create("/v1/trading/delivery-notes/" + noteId))
                .body(read(noteId, scope));
    }

    @Override
    public ResponseEntity<DeliveryNoteResponse> issueDeliveryNote(String idempotencyKey, UUID deliveryNoteId) {
        ScopeContext scope = currentScope.get();
        issue.handle(new IssueDeliveryNote(deliveryNoteId), scope);
        return ResponseEntity.ok(read(deliveryNoteId, scope));
    }

    @Override
    public ResponseEntity<DeliveryNoteResponse> dispatchDeliveryNote(
            String idempotencyKey, UUID deliveryNoteId, DispatchRequest request) {
        ScopeContext scope = currentScope.get();
        dispatch.handle(
                new DispatchDeliveryNote(
                        deliveryNoteId, request.getVehicleRef(), request.getDriverUserId(), request.getDriverName()),
                scope);
        return ResponseEntity.ok(read(deliveryNoteId, scope));
    }

    @Override
    public ResponseEntity<DeliveryNoteResponse> getDeliveryNote(UUID deliveryNoteId) {
        return ResponseEntity.ok(read(deliveryNoteId, currentScope.get()));
    }

    @Override
    public ResponseEntity<List<DeliveryNoteResponse>> listDeliveryNotes(String role) {
        return ResponseEntity.ok(queries.listDeliveryNotes(OrderQueries.Role.valueOf(role), currentScope.get()).stream()
                .map(DeliveryController::toResponse)
                .toList());
    }

    private DeliveryNoteResponse read(UUID noteId, ScopeContext scope) {
        return queries.getDeliveryNote(noteId, scope)
                .map(DeliveryController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.delivery.not_found"));
    }

    static DeliveryNoteResponse toResponse(DeliveryView note) {
        DeliveryNoteResponse response = new DeliveryNoteResponse(
                note.deliveryNoteId(),
                DeliveryNoteResponse.StatusEnum.fromValue(note.status()),
                note.sellerEntityId(),
                note.buyerEntityId(),
                note.drops().stream().map(DeliveryController::toDrop).toList());
        response.setDocNumber(note.docNumberDisplay());
        response.setFromLocationId(note.fromLocationId());
        response.setVehicleRef(note.vehicleRef());
        response.setDriverName(note.driverName());
        response.setDispatchedAt(note.dispatchedAt());
        response.setIssuedAt(note.issuedAt());
        return response;
    }

    private static DropResponse toDrop(DeliveryView.DropView drop) {
        DropResponse response = new DropResponse(
                drop.dropId(),
                drop.seq(),
                drop.shipToLocationId(),
                drop.billToEntityId(),
                drop.orderIds(),
                DropResponse.StatusEnum.fromValue(drop.status()),
                drop.lines().stream().map(DeliveryController::toLine).toList());
        response.setGrnId(drop.grnId());
        return response;
    }

    private static DeliveryLineResponse toLine(DeliveryLineSummary line) {
        DeliveryLineResponse response = new DeliveryLineResponse(
                line.lineId(),
                line.lineNo(),
                line.orderId(),
                line.orderLineId(),
                line.skuId(),
                line.uomCode(),
                line.dispatchedQty());
        response.setBatchId(line.batchId());
        return response;
    }
}
