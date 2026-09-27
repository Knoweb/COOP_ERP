package lk.coopfed.knoweb.m4trading.internal.order;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.CancelOrder;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.OrderAllocated;
import lk.coopfed.knoweb.m4trading.api.OrderRejected;
import lk.coopfed.knoweb.m4trading.api.RejectOrder;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** AcceptOrder and RejectOrder (24A section 6, section 6.2): every guard, the seller's rows, the derived status, audit and events. */
class AcceptanceHandlersPostgresIntegrationTest extends PostgresIntegrationTest {


    @Autowired
    CreateOrderHandler create;

    @Autowired
    SubmitOrderHandler submit;

    @Autowired
    CancelOrderHandler cancel;

    @Autowired
    AcceptOrderHandler accept;

    @Autowired
    RejectOrderHandler reject;

    @Autowired
    OrderQueries orders;

    private UUID orderId;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        orderId = create.handle(
                new CreateOrder(
                        SELLER,
                        today().plusDays(3),
                        null,
                        List.of(
                                new CreateOrder.Line(RICE, "EA", new BigDecimal("10")),
                                new CreateOrder.Line(DHAL, "EA", new BigDecimal("4")))),
                buyer());
        submit.handle(new SubmitOrder(orderId), buyer());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theSellerAcceptsAllocatingWhatIsOpenWithTheTierPriceAndTheLockTime() {
        LocalDate eta = today().plusDays(3);
        UUID runId = accept.handle(new AcceptOrder(orderId, eta, List.of()), seller());

        OrderView order = orders.getOrder(orderId, buyer()).orElseThrow();
        assertThat(order.status()).isEqualTo("ACCEPTED");
        assertThat(order.committedEta()).isEqualTo(eta);
        // The relationship's order_lock_hours_before_eta defaults to 24 (m1party V0001).
        assertThat(order.lockAt())
                .isEqualTo(eta.atStartOfDay(ZoneId.of("Asia/Colombo"))
                        .minusHours(24)
                        .toInstant());
        assertThat(order.lines()).allSatisfy(line -> {
            assertThat(line.allocatedQty()).isEqualByComparingTo(line.requestedQty());
            assertThat(line.fulfilledQty()).isEqualByComparingTo("0");
            assertThat(line.tierPrice()).isNotNull();
        });
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select rule from trading.allocation_run where run_id = ?", String.class, runId))
                .isEqualTo("FCFS");

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("ORDER_ACCEPTED");
        assertThat(events(OrderAccepted.class)).singleElement().satisfies(event -> {
            assertThat(event.allocationRunId()).isEqualTo(runId);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.lines()).hasSize(2);
        });
        assertThat(events(OrderAllocated.class)).hasSize(1);

        // Decided once: a second acceptance and a rejection are refused.
        kernel.reset();
        refused(() -> accept.handle(new AcceptOrder(orderId, eta, List.of()), seller()), "m4.order.already_decided");
        refused(() -> reject.handle(new RejectOrder(orderId, "NO_STOCK", null), seller()), "m4.order.already_decided");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theAllocationIsCappedByAvailabilityAndAnOverrideCarriesItsReason() {
        // M5 reports 6 rice in the seller's warehouse.
        TradingFixture.stock(superuserJdbc(), RICE, new BigDecimal("6"));
        OrderView before = orders.getOrder(orderId, seller()).orElseThrow();
        UUID riceLine = before.lines().get(0).lineId();
        UUID dhalLine = before.lines().get(1).lineId();

        refused(
                () -> accept.handle(
                        new AcceptOrder(
                                orderId,
                                today(),
                                List.of(new AcceptOrder.LineOverride(dhalLine, new BigDecimal("5"), "promo"))),
                        seller()),
                "m4.order.allocation_exceeds_request");
        refused(
                () -> accept.handle(
                        new AcceptOrder(
                                orderId,
                                today(),
                                List.of(new AcceptOrder.LineOverride(riceLine, new BigDecimal("8"), "all"))),
                        seller()),
                "m4.order.allocation_exceeds_available");
        refused(
                () -> accept.handle(
                        new AcceptOrder(
                                orderId, today(), List.of(new AcceptOrder.LineOverride(riceLine, BigDecimal.ONE, " "))),
                        seller()),
                "request.field.required");
        refused(
                () -> accept.handle(
                        new AcceptOrder(
                                orderId,
                                today(),
                                List.of(new AcceptOrder.LineOverride(UUID.randomUUID(), BigDecimal.ONE, "x"))),
                        seller()),
                "m4.order.line_unknown");
        assertThat(kernel.committedAudit()).isEmpty();

        accept.handle(
                new AcceptOrder(
                        orderId,
                        today(),
                        List.of(new AcceptOrder.LineOverride(dhalLine, new BigDecimal("2"), "ration"))),
                seller());

        OrderView order = orders.getOrder(orderId, seller()).orElseThrow();
        assertThat(order.lines().get(0).allocatedQty()).isEqualByComparingTo("6"); // capped by availability
        assertThat(order.lines().get(1).allocatedQty()).isEqualByComparingTo("2"); // the override
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select override_reason from trading.order_allocation_line where order_line_id = ?",
                                String.class,
                                dhalLine))
                .isEqualTo("ration");
    }

    @Test
    void theGuardsOfAcceptance() {
        refused(() -> accept.handle(new AcceptOrder(orderId, today(), List.of()), buyer()), "m4.order.not_seller");
        refused(
                () -> accept.handle(new AcceptOrder(orderId, today().minusDays(1), List.of()), seller()),
                "m4.order.eta_past");
        refused(() -> accept.handle(new AcceptOrder(orderId, null, List.of()), seller()), "request.field.required");
        refused(
                () -> accept.handle(new AcceptOrder(UUID.randomUUID(), today(), List.of()), seller()),
                "m4.order.not_found");

        cancel.handle(new CancelOrder(orderId, "CHANGED_MIND", null), buyer());
        kernel.reset();
        refused(() -> accept.handle(new AcceptOrder(orderId, today(), List.of()), seller()), "m4.order.not_submitted");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theSellerRejectsWithAReasonAndTheBuyerCanNoLongerCancel() {
        refused(() -> reject.handle(new RejectOrder(orderId, "NO_STOCK", null), buyer()), "m4.order.not_seller");
        refused(() -> reject.handle(new RejectOrder(orderId, "", null), seller()), "request.field.required");

        reject.handle(new RejectOrder(orderId, "NO_STOCK", "out until next month"), seller());

        OrderView order = orders.getOrder(orderId, buyer()).orElseThrow();
        assertThat(order.status()).isEqualTo("REJECTED");
        assertThat(order.rejectReasonCode()).isEqualTo("NO_STOCK");
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("ORDER_REJECTED");
        assertThat(events(OrderRejected.class)).singleElement().satisfies(event -> assertThat(event.reasonCode())
                .isEqualTo("NO_STOCK"));

        refused(() -> cancel.handle(new CancelOrder(orderId, "LATE", null), buyer()), "m4.order.not_cancellable");
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
