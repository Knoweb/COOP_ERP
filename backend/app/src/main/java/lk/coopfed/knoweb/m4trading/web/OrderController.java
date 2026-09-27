package lk.coopfed.knoweb.m4trading.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.CancelOrder;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.InventoryAvailability;
import lk.coopfed.knoweb.m4trading.api.RejectOrder;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.internal.order.AcceptOrderHandler;
import lk.coopfed.knoweb.m4trading.internal.order.CancelOrderHandler;
import lk.coopfed.knoweb.m4trading.internal.order.CreateOrderHandler;
import lk.coopfed.knoweb.m4trading.internal.order.RejectOrderHandler;
import lk.coopfed.knoweb.m4trading.internal.order.SubmitOrderHandler;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.m4trading.web.generated.AcceptOrderRequest;
import lk.coopfed.knoweb.m4trading.web.generated.AvailabilityResponse;
import lk.coopfed.knoweb.m4trading.web.generated.CreateOrderRequest;
import lk.coopfed.knoweb.m4trading.web.generated.OrderApi;
import lk.coopfed.knoweb.m4trading.web.generated.OrderLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.OrderResponse;
import lk.coopfed.knoweb.m4trading.web.generated.OrderStatus;
import lk.coopfed.knoweb.m4trading.web.generated.ReasonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The order operations of 24A section 5 (OrderController): draft, submit, cancel, read, availability. */
@RestController
class OrderController implements OrderApi {

    private final CreateOrderHandler create;
    private final SubmitOrderHandler submit;
    private final CancelOrderHandler cancel;
    private final AcceptOrderHandler accept;
    private final RejectOrderHandler reject;
    private final OrderQueries queries;
    private final InventoryAvailability availability;
    private final CurrentScope currentScope;

    OrderController(
            CreateOrderHandler create,
            SubmitOrderHandler submit,
            CancelOrderHandler cancel,
            AcceptOrderHandler accept,
            RejectOrderHandler reject,
            OrderQueries queries,
            InventoryAvailability availability,
            CurrentScope currentScope) {
        this.create = create;
        this.submit = submit;
        this.cancel = cancel;
        this.accept = accept;
        this.reject = reject;
        this.queries = queries;
        this.availability = availability;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<OrderResponse> createOrder(String idempotencyKey, CreateOrderRequest request) {
        ScopeContext scope = currentScope.get();
        List<CreateOrder.Line> lines = request.getLines().stream()
                .map(line -> new CreateOrder.Line(line.getSkuId(), line.getUomCode(), line.getQty()))
                .toList();
        UUID orderId = create.handle(
                new CreateOrder(
                        request.getSellerEntityId(),
                        request.getRequestedEta(),
                        request.getNotes(),
                        lines,
                        request.getDeliverToLocationId()),
                scope);
        return ResponseEntity.created(URI.create("/v1/trading/orders/" + orderId))
                .body(read(orderId, scope));
    }

    @Override
    public ResponseEntity<OrderResponse> submitOrder(String idempotencyKey, UUID orderId) {
        ScopeContext scope = currentScope.get();
        submit.handle(new SubmitOrder(orderId), scope);
        return ResponseEntity.ok(read(orderId, scope));
    }

    @Override
    public ResponseEntity<OrderResponse> cancelOrder(String idempotencyKey, UUID orderId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        cancel.handle(new CancelOrder(orderId, request.getReasonCode(), request.getReasonText()), scope);
        return ResponseEntity.ok(read(orderId, scope));
    }

    @Override
    public ResponseEntity<OrderResponse> acceptOrder(String idempotencyKey, UUID orderId, AcceptOrderRequest request) {
        ScopeContext scope = currentScope.get();
        List<AcceptOrder.LineOverride> overrides = request.getOverrides() == null
                ? List.of()
                : request.getOverrides().stream()
                        .map(o -> new AcceptOrder.LineOverride(o.getLineId(), o.getAllocatedQty(), o.getReason()))
                        .toList();
        accept.handle(new AcceptOrder(orderId, request.getCommittedEta(), overrides), scope);
        return ResponseEntity.ok(read(orderId, scope));
    }

    @Override
    public ResponseEntity<OrderResponse> rejectOrder(String idempotencyKey, UUID orderId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        reject.handle(new RejectOrder(orderId, request.getReasonCode(), request.getReasonText()), scope);
        return ResponseEntity.ok(read(orderId, scope));
    }

    @Override
    public ResponseEntity<OrderResponse> getOrder(UUID orderId) {
        return ResponseEntity.ok(read(orderId, currentScope.get()));
    }

    @Override
    public ResponseEntity<List<OrderResponse>> listOrders(String role, OrderStatus status) {
        OrderQueries.Role asked = OrderQueries.Role.valueOf(role);
        return ResponseEntity.ok(
                queries.listOrders(asked, status == null ? null : status.getValue(), currentScope.get()).stream()
                        .map(OrderController::toResponse)
                        .toList());
    }

    @Override
    public ResponseEntity<List<AvailabilityResponse>> sellerAvailability(UUID sellerId, List<UUID> skuIds) {
        Map<UUID, BigDecimal> available = availability.availability(sellerId, skuIds, currentScope.get());
        return ResponseEntity.ok(skuIds.stream()
                .distinct()
                .map(sku -> new AvailabilityResponse(sku, available.getOrDefault(sku, BigDecimal.ZERO)))
                .toList());
    }

    private OrderResponse read(UUID orderId, ScopeContext scope) {
        return queries.getOrder(orderId, scope)
                .map(OrderController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.order.not_found"));
    }

    static OrderResponse toResponse(OrderView order) {
        OrderResponse response = new OrderResponse(
                order.orderId(),
                OrderStatus.fromValue(order.status()),
                order.relationshipId(),
                order.buyerEntityId(),
                order.sellerEntityId(),
                order.lines().stream().map(OrderController::toLine).toList());
        response.setDocNumber(order.docNumberDisplay());
        response.setRequestedEta(order.requestedEta());
        response.setCommittedEta(order.committedEta());
        response.setLockAt(order.lockAt());
        response.setSubmittedAt(order.submittedAt());
        response.setRejectReasonCode(order.rejectReasonCode());
        response.setNetAmount(order.netAmount());
        response.setNotes(order.notes());
        response.setDeliverToLocationId(order.deliverToLocationId());
        return response;
    }

    private static OrderLineResponse toLine(OrderView.OrderLineView line) {
        OrderLineResponse response = new OrderLineResponse(
                line.lineId(), line.lineNo(), line.skuId(), line.uomCode(), line.requestedQty(), line.cancelledQty());
        response.setIndicativePrice(line.indicativePrice());
        response.setAllocatedQty(line.allocatedQty());
        response.setFulfilledQty(line.fulfilledQty());
        response.setTierPrice(line.tierPrice());
        return response;
    }
}
