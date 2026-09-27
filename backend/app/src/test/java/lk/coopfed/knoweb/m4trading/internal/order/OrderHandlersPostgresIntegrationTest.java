package lk.coopfed.knoweb.m4trading.internal.order;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DRAFT_SKU;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RELATIONSHIP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyerAt;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.api.CancelOrder;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.OrderCancelled;
import lk.coopfed.knoweb.m4trading.api.OrderCreated;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** CreateOrder, SubmitOrder and CancelOrder (24A section 6): every guard with its failing case, the rows, audit and events. */
class OrderHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    CreateOrderHandler create;

    @Autowired
    SubmitOrderHandler submit;

    @Autowired
    CancelOrderHandler cancel;

    @Autowired
    OrderQueries orders;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theBuyerDraftsAnOrderPricedAtTheIndicativeTradePrice() {
        UUID orderId = create.handle(twoLines(), buyer());

        OrderView order = orders.getOrder(orderId, buyer()).orElseThrow();
        assertThat(order.status()).isEqualTo("DRAFT");
        assertThat(order.docNumberDisplay()).isNull();
        assertThat(order.relationshipId()).isEqualTo(RELATIONSHIP);
        assertThat(order.lines()).hasSize(2);
        assertThat(order.lines().get(0).requestedQty()).isEqualByComparingTo("10");
        // m4.demo.trade_price, the demo answer of TradePricing, is 100.00 per unit.
        assertThat(order.lines().get(0).indicativePrice()).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("ORDER_CREATED");
        assertThat(events(OrderCreated.class)).singleElement().satisfies(event -> {
            assertThat(event.orderId()).isEqualTo(orderId);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.lines()).hasSize(2);
        });
    }

    @Test
    void submittingIssuesTheOrderFromTheBuyersSeriesAndTheSellerSeesIt() {
        UUID orderId = create.handle(twoLines(), buyer());
        kernel.reset();

        String number = submit.handle(new SubmitOrder(orderId), buyer());

        assertThat(number).isEqualTo("D4B-ORD-0000001");
        OrderView asSeller = orders.getOrder(orderId, seller()).orElseThrow();
        assertThat(asSeller.status()).isEqualTo("SUBMITTED");
        assertThat(asSeller.docNumberDisplay()).isEqualTo(number);
        assertThat(asSeller.submittedAt()).isNotNull();
        assertThat(orders.listOrders(OrderQueries.Role.SELLER, null, seller()))
                .extracting(OrderView::orderId)
                .containsExactly(orderId);
        assertThat(orders.listOrders(OrderQueries.Role.BUYER, "SUBMITTED", buyer()))
                .extracting(OrderView::orderId)
                .containsExactly(orderId);
        assertThat(orders.getOrder(orderId, ScopeContext.dev(UUID.randomUUID(), STRANGER, null)))
                .isEmpty();

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DOCUMENT_ISSUED", "ORDER_SUBMITTED");
        assertThat(events(OrderSubmitted.class)).singleElement().satisfies(event -> {
            assertThat(event.docNumberDisplay()).isEqualTo(number);
            assertThat(event.lines()).hasSize(2);
        });

        // A second order takes the next number of the same series.
        UUID second = create.handle(twoLines(), buyer());
        assertThat(submit.handle(new SubmitOrder(second), buyer())).isEqualTo("D4B-ORD-0000002");
    }

    @Test
    void theGuardsOfCreateOrderRefuseBeforeAnythingIsWritten() {
        refused(() -> create.handle(order(BUYER, line(RICE, "5")), buyer()), "m4.order.seller_is_buyer");
        refused(() -> create.handle(order(STRANGER, line(RICE, "5")), buyer()), "m4.order.relationship_inactive");
        refused(
                () -> create.handle(
                        new CreateOrder(SELLER, today().minusDays(1), null, List.of(line(RICE, "5"))), buyer()),
                "m4.order.eta_past");
        refused(
                () -> create.handle(new CreateOrder(SELLER, null, null, List.of()), buyer()),
                "m4.order.lines_required");
        refused(() -> create.handle(order(SELLER, line(UUID.randomUUID(), "5")), buyer()), "m4.order.sku_not_found");
        refused(() -> create.handle(order(SELLER, line(DRAFT_SKU, "5")), buyer()), "m4.order.sku_not_active");
        refused(
                () -> create.handle(order(SELLER, new CreateOrder.Line(RICE, "CASE", new BigDecimal("5"))), buyer()),
                "m4.order.uom_invalid");
        refused(() -> create.handle(order(SELLER, line(RICE, "0")), buyer()), "m4.order.qty_not_positive");
        refused(() -> create.handle(order(SELLER, line(RICE, "5")), buyerAt(TradingFixture.SHOP)), "scope.invalid");

        assertThat(orders.listOrders(OrderQueries.Role.BUYER, null, buyer())).isEmpty();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void onlyTheBuyerSubmitsItsDraftAndOnlyOnce() {
        UUID orderId = create.handle(twoLines(), buyer());
        kernel.reset();

        refused(() -> submit.handle(new SubmitOrder(orderId), seller()), "m4.order.not_buyer");
        refused(() -> submit.handle(new SubmitOrder(UUID.randomUUID()), buyer()), "m4.order.not_found");
        submit.handle(new SubmitOrder(orderId), buyer());
        kernel.reset();
        refused(() -> submit.handle(new SubmitOrder(orderId), buyer()), "m4.order.not_draft");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void submittingNeedsTheRelationshipStillActive() {
        UUID orderId = create.handle(twoLines(), buyer());
        superuserJdbc()
                .update(
                        "update party.entity_relationship set status = 'SUSPENDED' where relationship_id = ?",
                        RELATIONSHIP);
        kernel.reset();

        refused(() -> submit.handle(new SubmitOrder(orderId), buyer()), "m4.order.relationship_inactive");
        assertThat(orders.getOrder(orderId, buyer()).orElseThrow().status()).isEqualTo("DRAFT");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theBuyerCancelsASubmittedOrderWhileNothingIsDispatched() {
        UUID orderId = create.handle(twoLines(), buyer());
        submit.handle(new SubmitOrder(orderId), buyer());
        kernel.reset();

        refused(() -> cancel.handle(new CancelOrder(orderId, "CHANGED_MIND", null), seller()), "m4.order.not_buyer");
        refused(() -> cancel.handle(new CancelOrder(orderId, " ", null), buyer()), "request.field.required");
        cancel.handle(new CancelOrder(orderId, "CHANGED_MIND", "not needed"), buyer());

        OrderView order = orders.getOrder(orderId, seller()).orElseThrow();
        assertThat(order.status()).isEqualTo("CANCELLED");
        assertThat(order.lines())
                .allSatisfy(line -> assertThat(line.cancelledQty()).isEqualByComparingTo(line.requestedQty()));
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("ORDER_CANCELLED");
        assertThat(events(OrderCancelled.class)).singleElement().satisfies(event -> {
            assertThat(event.cancelledByEntityId()).isEqualTo(BUYER);
            assertThat(event.reasonCode()).isEqualTo("CHANGED_MIND");
        });

        kernel.reset();
        refused(() -> cancel.handle(new CancelOrder(orderId, "AGAIN", null), buyer()), "m4.order.not_cancellable");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void anOrderPartlyFulfilledIsNotCancelled() {
        UUID orderId = create.handle(twoLines(), buyer());
        submit.handle(new SubmitOrder(orderId), buyer());
        // The seller's allocation with a dispatched quantity, as M4-03 and M4-04 write it.
        UUID lineId =
                orders.getOrder(orderId, buyer()).orElseThrow().lines().get(0).lineId();
        superuserJdbc()
                .update(
                        """
                        insert into trading.order_allocation (order_id, owner_entity_id, counterparty_entity_id,
                            relationship_id, status, committed_eta, lock_at)
                        values (?, ?, ?, ?, 'ACCEPTED', current_date + 3, now() + interval '2 days')
                        """,
                        orderId,
                        SELLER,
                        BUYER,
                        RELATIONSHIP);
        superuserJdbc()
                .update(
                        """
                        insert into trading.order_allocation_line (order_line_id, order_id, owner_entity_id,
                            counterparty_entity_id, allocated_qty, fulfilled_qty)
                        values (?, ?, ?, ?, 10, 4)
                        """,
                        lineId,
                        orderId,
                        SELLER,
                        BUYER);
        assertThat(orders.getOrder(orderId, buyer()).orElseThrow().status()).isEqualTo("PARTIALLY_FULFILLED");
        kernel.reset();

        refused(() -> cancel.handle(new CancelOrder(orderId, "LATE", null), buyer()), "m4.order.dispatched");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static CreateOrder twoLines() {
        return new CreateOrder(SELLER, today().plusDays(3), "first order", List.of(line(RICE, "10"), line(DHAL, "4")));
    }

    private static CreateOrder order(UUID seller, CreateOrder.Line line) {
        return new CreateOrder(seller, null, null, List.of(line));
    }

    private static CreateOrder.Line line(UUID sku, String qty) {
        return new CreateOrder.Line(sku, "EA", new BigDecimal(qty));
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
