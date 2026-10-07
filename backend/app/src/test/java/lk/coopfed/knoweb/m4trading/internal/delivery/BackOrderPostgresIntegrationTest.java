package lk.coopfed.knoweb.m4trading.internal.delivery;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CreateDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(TradingFlow.class)
class BackOrderPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CreateDeliveryNoteHandler create;

    @Autowired
    IssueDeliveryNoteHandler issue;

    @Autowired
    OrderQueries orders;

    private OrderView order;
    private UUID riceLine;
    private UUID sugarLine;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        order = flow.acceptedOrder(); // 10 Rice, 5 Sugar
        riceLine = order.lines().get(0).lineId();
        sugarLine = order.lines().get(1).lineId();
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @org.springframework.beans.factory.annotation.Autowired
    lk.coopfed.knoweb.m4trading.internal.order.CreateOrderHandler createOrder;

    @org.springframework.beans.factory.annotation.Autowired
    lk.coopfed.knoweb.m4trading.internal.order.SubmitOrderHandler submitOrder;

    @org.springframework.beans.factory.annotation.Autowired
    lk.coopfed.knoweb.m4trading.internal.order.AcceptOrderHandler acceptOrder;

    @Test
    void fullyFulfilledOrderHasNoBackOrder() {
        UUID noteId = flow.draftNote(order, SHOP);
        issue.handle(new IssueDeliveryNote(noteId), seller());

        OrderView view = orders.getOrder(order.orderId(), buyer()).orElseThrow();
        assertThat(view.status()).isEqualTo("FULFILLED");
        assertThat(view.lines()).allSatisfy(line -> {
            assertThat(line.allocatedQty()).isEqualByComparingTo(line.fulfilledQty());
            assertThat(line.backOrderQty()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void partialDeliveryCreatesBackOrderWhichReducesAndClosesOnLaterDeliveries() {
        // 1. Partial delivery
        UUID note1 = create.handle(
                new CreateDeliveryNote(
                        null,
                        null,
                        null,
                        List.of(new CreateDeliveryNote.Drop(
                                SHOP,
                                BUYER,
                                List.of(
                                        new CreateDeliveryNote.Line(riceLine, new BigDecimal("4"), null),
                                        new CreateDeliveryNote.Line(sugarLine, new BigDecimal("4"), null))))),
                seller());
        issue.handle(new IssueDeliveryNote(note1), seller());

        OrderView view1 = orders.getOrder(order.orderId(), buyer()).orElseThrow();
        assertThat(view1.status()).isEqualTo("PARTIALLY_FULFILLED");

        OrderView.OrderLineView rice1 = view1.lines().stream()
                .filter(l -> l.lineId().equals(riceLine))
                .findFirst()
                .orElseThrow();
        assertThat(rice1.allocatedQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(rice1.fulfilledQty()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(rice1.backOrderQty()).isEqualByComparingTo(new BigDecimal("6")); // back order!

        // 2. Second delivery (partial again)
        UUID note2 = create.handle(
                new CreateDeliveryNote(
                        null,
                        null,
                        null,
                        List.of(new CreateDeliveryNote.Drop(
                                SHOP,
                                BUYER,
                                List.of(new CreateDeliveryNote.Line(riceLine, new BigDecimal("3"), null))))),
                seller());
        issue.handle(new IssueDeliveryNote(note2), seller());

        OrderView view2 = orders.getOrder(order.orderId(), seller()).orElseThrow();
        assertThat(view2.status()).isEqualTo("PARTIALLY_FULFILLED");
        OrderView.OrderLineView rice2 = view2.lines().stream()
                .filter(l -> l.lineId().equals(riceLine))
                .findFirst()
                .orElseThrow();
        assertThat(rice2.allocatedQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(rice2.fulfilledQty()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(rice2.backOrderQty()).isEqualByComparingTo(new BigDecimal("3")); // reduces

        // 3. Final delivery
        UUID note3 = create.handle(
                new CreateDeliveryNote(
                        null,
                        null,
                        null,
                        List.of(new CreateDeliveryNote.Drop(
                                SHOP,
                                BUYER,
                                List.of(new CreateDeliveryNote.Line(riceLine, new BigDecimal("3"), null))))),
                seller());
        issue.handle(new IssueDeliveryNote(note3), seller());

        OrderView view3 = orders.getOrder(order.orderId(), buyer()).orElseThrow();
        assertThat(view3.status()).isEqualTo("FULFILLED");
        OrderView.OrderLineView rice3 = view3.lines().stream()
                .filter(l -> l.lineId().equals(riceLine))
                .findFirst()
                .orElseThrow();
        assertThat(rice3.backOrderQty()).isEqualByComparingTo(BigDecimal.ZERO); // closes
    }

    @Test
    void overDeliveryRejected() {
        // First deliver 6
        UUID note1 = create.handle(
                new CreateDeliveryNote(
                        null,
                        null,
                        null,
                        List.of(new CreateDeliveryNote.Drop(
                                SHOP,
                                BUYER,
                                List.of(new CreateDeliveryNote.Line(riceLine, new BigDecimal("6"), null))))),
                seller());
        issue.handle(new IssueDeliveryNote(note1), seller());

        // Try to deliver 5 (6 + 5 = 11 > 10)
        assertThatThrownBy(() -> create.handle(
                        new CreateDeliveryNote(
                                null,
                                null,
                                null,
                                List.of(new CreateDeliveryNote.Drop(
                                        SHOP,
                                        BUYER,
                                        List.of(new CreateDeliveryNote.Line(riceLine, new BigDecimal("5"), null))))),
                        seller()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m4.delivery.exceeds_allocation");
    }

    @Test
    void backOrderQtyIsBasedOnRequestedQtyEvenIfPartiallyAllocated() {
        // We set stock to 6 RICE
        TradingFixture.stock(superuserJdbc(), lk.coopfed.knoweb.m4trading.TradingFixture.RICE, new BigDecimal("6"));

        // Create a new order for 10 RICE
        UUID orderId = createOrder.handle(
                new lk.coopfed.knoweb.m4trading.api.CreateOrder(
                        lk.coopfed.knoweb.m4trading.TradingFixture.SELLER,
                        java.time.LocalDate.now().plusDays(3),
                        null,
                        List.of(new lk.coopfed.knoweb.m4trading.api.CreateOrder.Line(
                                lk.coopfed.knoweb.m4trading.TradingFixture.RICE, "EA", new BigDecimal("10")))),
                buyer());

        submitOrder.handle(new lk.coopfed.knoweb.m4trading.api.SubmitOrder(orderId), buyer());

        acceptOrder.handle(
                new lk.coopfed.knoweb.m4trading.api.AcceptOrder(
                        orderId, java.time.LocalDate.now().plusDays(3), List.of()),
                seller());

        OrderView view = orders.getOrder(orderId, buyer()).orElseThrow();
        OrderView.OrderLineView rice = view.lines().get(0);

        // Request 10, Stock 6 -> Allocated 6, Fulfilled 0
        assertThat(rice.requestedQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(rice.allocatedQty()).isEqualByComparingTo(new BigDecimal("6"));

        // Requirement: backOrderQty must report 10, not 6
        assertThat(rice.backOrderQty()).isEqualByComparingTo(new BigDecimal("10"));

        // Now deliver 4
        UUID n = create.handle(
                new CreateDeliveryNote(
                        null,
                        null,
                        null,
                        List.of(new CreateDeliveryNote.Drop(
                                SHOP,
                                BUYER,
                                List.of(new CreateDeliveryNote.Line(rice.lineId(), new BigDecimal("4"), null))))),
                seller());
        issue.handle(new IssueDeliveryNote(n), seller());

        OrderView viewAfter = orders.getOrder(orderId, buyer()).orElseThrow();
        OrderView.OrderLineView riceAfter = viewAfter.lines().get(0);

        // Delivered 4. Remaining back order is 10 - 4 = 6
        assertThat(riceAfter.fulfilledQty()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(riceAfter.backOrderQty()).isEqualByComparingTo(new BigDecimal("6"));
    }
}
