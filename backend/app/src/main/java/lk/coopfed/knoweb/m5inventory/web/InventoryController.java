package lk.coopfed.knoweb.m5inventory.web;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.CountersignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceLine;
import lk.coopfed.knoweb.m5inventory.api.PrepareOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.ReceiveTransfer;
import lk.coopfed.knoweb.m5inventory.api.SignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.MovementView;
import lk.coopfed.knoweb.m5inventory.query.OpeningBalanceView;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
import lk.coopfed.knoweb.m5inventory.query.TransferView;
import lk.coopfed.knoweb.m5inventory.web.generated.AvailabilityResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.InventoryApi;
import lk.coopfed.knoweb.m5inventory.web.generated.IssueTransferRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.LotBalanceResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.MovementResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.OpeningBalanceLineResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.OpeningBalanceResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.PickListResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.PickResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.PrepareOpeningBalanceRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.StockCardLineResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.TransferLineResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.TransferResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of M5 for the demo (25A section 5): the reads (balances, availability, a GRN's
 * receipt, a delivery note's pick list, an opening balance) and the opening balance's three
 * commands. The kernel checks each operation's x-permission; row-level security decides which
 * rows the caller sees. The unit cost of a lot is shown to a user of the owning entity only: never
 * to a till (25A section 9: "till-scoped balances carry no cost") and never to a read-only class
 * (section 5: "unitCost? (OWN only)").
 */
@RestController
class InventoryController implements InventoryApi {

    private final InventoryQueries queries;
    private final BatchQueries batches;
    private final CurrentScope currentScope;
    private final Handles<PrepareOpeningBalance, UUID> prepare;
    private final Handles<SignOpeningBalance, UUID> sign;
    private final Handles<CountersignOpeningBalance, UUID> countersign;
    private final Handles<IssueTransfer, UUID> issueTransfer;
    private final Handles<ReceiveTransfer, UUID> receiveTransfer;

    InventoryController(
            InventoryQueries queries,
            BatchQueries batches,
            CurrentScope currentScope,
            Handles<PrepareOpeningBalance, UUID> prepare,
            Handles<SignOpeningBalance, UUID> sign,
            Handles<CountersignOpeningBalance, UUID> countersign,
            Handles<IssueTransfer, UUID> issueTransfer,
            Handles<ReceiveTransfer, UUID> receiveTransfer) {
        this.queries = queries;
        this.batches = batches;
        this.currentScope = currentScope;
        this.prepare = prepare;
        this.sign = sign;
        this.countersign = countersign;
        this.issueTransfer = issueTransfer;
        this.receiveTransfer = receiveTransfer;
    }

    // ---- transfers (M5-09) ----------------------------------------------------------------

    @Override
    public ResponseEntity<List<TransferResponse>> listTransfers(UUID locationId) {
        return ResponseEntity.ok(queries.transfers(locationId, currentScope.get()).stream()
                .map(InventoryController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<TransferResponse> getTransfer(UUID transferId) {
        return queries.transfer(transferId, currentScope.get())
                .map(InventoryController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<TransferResponse> issueTransfer(String idempotencyKey, IssueTransferRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = issueTransfer.handle(
                new IssueTransfer(
                        request.getFromLocationId(),
                        request.getToLocationId(),
                        request.getLines().stream()
                                .map(l -> new IssueTransfer.Line(l.getBatchId(), l.getQty()))
                                .toList()),
                scope);
        return ResponseEntity.created(URI.create("/v1/inventory/transfers/" + id))
                .body(toResponse(queries.transfer(id, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<TransferResponse> receiveTransfer(String idempotencyKey, UUID transferId) {
        ScopeContext scope = currentScope.get();
        receiveTransfer.handle(new ReceiveTransfer(transferId), scope);
        return ResponseEntity.ok(toResponse(queries.transfer(transferId, scope).orElseThrow()));
    }

    private static TransferResponse toResponse(TransferView t) {
        return new TransferResponse(
                        t.transferId(),
                        t.fromLocationId(),
                        t.toLocationId(),
                        TransferResponse.StatusEnum.fromValue(t.status()),
                        t.lines().stream()
                                .map(l -> new TransferLineResponse(l.lineNo(), l.batchId(), l.skuId(), l.qty()))
                                .toList())
                .issuedBy(t.issuedBy())
                .issuedAt(t.issuedAt())
                .receivedBy(t.receivedBy())
                .receivedAt(t.receivedAt());
    }

    // ---- reads ----------------------------------------------------------------------------

    @Override
    public ResponseEntity<List<LotBalanceResponse>> listBalances(UUID locationId, UUID skuId, Boolean includeZero) {
        ScopeContext scope = currentScope.get();
        boolean showCost = scope.policyClass() == PolicyClass.OWN && scope.deviceId() == null;
        Map<UUID, String> batchNumbers = new HashMap<>();

        List<LotBalanceResponse> lots =
                queries.balances(locationId, skuId, Boolean.TRUE.equals(includeZero), scope).stream()
                        .map(lot -> toResponse(lot, showCost, batchNo(batchNumbers, lot.batchId(), scope)))
                        .toList();
        return ResponseEntity.ok(lots);
    }

    @Override
    public ResponseEntity<List<StockCardLineResponse>> listMovements(UUID locationId, UUID skuId) {
        ScopeContext scope = currentScope.get();
        boolean showCost = scope.policyClass() == PolicyClass.OWN && scope.deviceId() == null;
        return ResponseEntity.ok(queries.stockCard(locationId, skuId, scope).stream()
                .map(line -> {
                    MovementResponse movement = toResponse(line.movement());
                    return new StockCardLineResponse(
                            showCost ? movement : movement.unitCostAtMovement(null), line.balanceAfter());
                })
                .toList());
    }

    @Override
    public ResponseEntity<List<AvailabilityResponse>> getAvailability(List<UUID> locationIds, List<UUID> skuIds) {
        return ResponseEntity.ok(queries.availability(locationIds, skuIds, currentScope.get()).stream()
                .map(a -> new AvailabilityResponse(a.locationId(), a.skuId(), a.available()))
                .toList());
    }

    @Override
    public ResponseEntity<List<MovementResponse>> getReceipt(UUID grnId) {
        return ResponseEntity.ok(queries.movementsOf(grnId, currentScope.get()).stream()
                .map(InventoryController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<PickListResponse> getPickList(UUID deliveryNoteId) {
        return queries.pickList(deliveryNoteId, currentScope.get())
                .map(InventoryController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<OpeningBalanceResponse> getOpeningBalance(UUID openingBalanceId) {
        return queries.openingBalance(openingBalanceId, currentScope.get())
                .map(InventoryController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- the opening balance --------------------------------------------------------------

    @Override
    public ResponseEntity<OpeningBalanceResponse> prepareOpeningBalance(
            String idempotencyKey, PrepareOpeningBalanceRequest request) {
        ScopeContext scope = currentScope.get();
        List<OpeningBalanceLine> lines = request.getLines().stream()
                .map(line -> new OpeningBalanceLine(
                        line.getBatchId(),
                        line.getCondition() == null
                                ? LotCondition.GOOD
                                : LotCondition.valueOf(line.getCondition().getValue()),
                        line.getQty(),
                        line.getUnitCost(),
                        line.getSkuId(),
                        line.getBatchNo(),
                        line.getExpiryDate(),
                        line.getPrintedMrp()))
                .toList();
        UUID id = prepare.handle(new PrepareOpeningBalance(request.getLocationId(), lines), scope);
        return ResponseEntity.created(URI.create("/v1/inventory/opening-balances/" + id))
                .body(toResponse(queries.openingBalance(id, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<OpeningBalanceResponse> signOpeningBalance(String idempotencyKey, UUID openingBalanceId) {
        ScopeContext scope = currentScope.get();
        sign.handle(new SignOpeningBalance(openingBalanceId), scope);
        return ResponseEntity.ok(
                toResponse(queries.openingBalance(openingBalanceId, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<OpeningBalanceResponse> countersignOpeningBalance(
            String idempotencyKey, UUID openingBalanceId) {
        ScopeContext scope = currentScope.get();
        countersign.handle(new CountersignOpeningBalance(openingBalanceId), scope);
        return ResponseEntity.ok(
                toResponse(queries.openingBalance(openingBalanceId, scope).orElseThrow()));
    }

    // ---- mappers --------------------------------------------------------------------------

    /** The printed batch number, from M2, read once per batch of the answer. */
    private String batchNo(Map<UUID, String> known, UUID batchId, ScopeContext scope) {
        if (!known.containsKey(batchId)) {
            known.put(
                    batchId,
                    batches.getBatch(batchId, scope).map(BatchView::batchNo).orElse(null));
        }
        return known.get(batchId);
    }

    private static LotBalanceResponse toResponse(LotBalance lot, boolean showCost, String batchNo) {
        LotBalanceResponse response = new LotBalanceResponse(
                        lot.stockLotId(),
                        lot.locationId(),
                        lot.skuId(),
                        lot.batchId(),
                        LotBalanceResponse.ConditionEnum.fromValue(lot.condition()),
                        lot.qtyOnHand(),
                        lot.negative())
                .batchNo(batchNo)
                .expiryDate(lot.expiryDate())
                .fefoRank(lot.fefoRank());
        return showCost ? response.unitCost(lot.unitCost()) : response;
    }

    private static MovementResponse toResponse(MovementView m) {
        return new MovementResponse(
                        m.movementId(),
                        m.locationId(),
                        m.skuId(),
                        m.batchId(),
                        MovementResponse.ConditionEnum.fromValue(m.condition()),
                        m.movementType(),
                        m.qtyDelta(),
                        m.documentId(),
                        m.movementSeq())
                .unitCostAtMovement(m.unitCostAtMovement())
                .documentLineId(m.documentLineId())
                .occurredAt(m.occurredAt());
    }

    private static PickListResponse toResponse(PickListView list) {
        return new PickListResponse(
                list.pickListId(),
                list.deliveryDocumentId(),
                PickListResponse.StatusEnum.fromValue(list.status()),
                list.lines().stream()
                        .map(p -> new PickResponse(p.deliveryLineId(), p.skuId(), p.qty())
                                .locationId(p.locationId())
                                .stockLotId(p.stockLotId())
                                .batchId(p.batchId()))
                        .toList());
    }

    private static OpeningBalanceResponse toResponse(OpeningBalanceView balance) {
        return new OpeningBalanceResponse(
                        balance.openingBalanceId(),
                        balance.locationId(),
                        OpeningBalanceResponse.StatusEnum.fromValue(balance.status()),
                        balance.lines().stream()
                                .map(l -> new OpeningBalanceLineResponse(
                                        l.lineNo(),
                                        l.batchId(),
                                        l.skuId(),
                                        OpeningBalanceLineResponse.ConditionEnum.fromValue(l.condition()),
                                        l.qty(),
                                        l.unitCost()))
                                .toList())
                .preparedBy(balance.preparedBy())
                .signedEntityBy(balance.signedEntityBy())
                .countersignedBy(balance.countersignedBy())
                .documentId(balance.documentId());
    }
}
