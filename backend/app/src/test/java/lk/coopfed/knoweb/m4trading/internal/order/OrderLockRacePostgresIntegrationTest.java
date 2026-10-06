package lk.coopfed.knoweb.m4trading.internal.order;

import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.AmendOrder;
import lk.coopfed.knoweb.m4trading.api.CancelOrder;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.internal.delivery.IssueDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wave 2, M4MONEY-06 and -07 (decision (3) of
 * docs/progress/deviations/2026-10-06-wave2-payments-and-cheques.md): the buyer's AmendOrder and
 * CancelOrder and the seller's AcceptOrder and IssueDeliveryNote meet on one advisory lock per
 * order. Each test runs the first act in its own transaction, held open for a moment after the act,
 * and the other party's act on a second thread meanwhile: the second waits for the lock and then
 * sees what the first committed. Before the fix both committed (the verifier's two-thread
 * reproductions, which passed only while the findings held).
 */
@Import(TradingFlow.class)
class OrderLockRacePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final long HOLD_MILLIS = 2000;

    @Autowired
    TradingFlow flow;

    @Autowired
    CreateOrderHandler createOrder;

    @Autowired
    SubmitOrderHandler submitOrder;

    @Autowired
    AcceptOrderHandler acceptOrder;

    @Autowired
    AmendOrderHandler amendOrder;

    @Autowired
    CancelOrderHandler cancelOrder;

    @Autowired
    IssueDeliveryNoteHandler issueNote;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    private UUID submittedOrder() {
        UUID orderId = createOrder.handle(
                new CreateOrder(
                        SELLER,
                        today().plusDays(3),
                        null,
                        List.of(
                                new CreateOrder.Line(RICE, "EA", new BigDecimal("10")),
                                new CreateOrder.Line(DHAL, "EA", new BigDecimal("4")))),
                buyer());
        submitOrder.handle(new SubmitOrder(orderId), buyer());
        return orderId;
    }

    @Test
    void anAmendmentHeldOpenMakesTheSellersAcceptanceWaitAndFail() throws Exception {
        UUID orderId = submittedOrder();

        Race race = race(
                () -> amendOrder.handle(
                        new AmendOrder(
                                orderId,
                                today().plusDays(5),
                                null,
                                List.of(new CreateOrder.Line(RICE, "EA", new BigDecimal("6")))),
                        buyer()),
                () -> acceptOrder.handle(new AcceptOrder(orderId, today().plusDays(3), List.of()), seller()));

        assertThat(race.first()).isInstanceOf(UUID.class);
        assertThat(race.second())
                .isInstanceOfSatisfying(ProblemException.class, problem -> assertThat(problem.messageId())
                        .isEqualTo("m4.order.not_submitted"));
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(allocations(orderId)).isZero();
    }

    @Test
    void anAcceptanceHeldOpenMakesTheBuyersAmendmentWaitAndFail() throws Exception {
        UUID orderId = submittedOrder();

        Race race = race(
                () -> acceptOrder.handle(new AcceptOrder(orderId, today().plusDays(3), List.of()), seller()),
                () -> amendOrder.handle(
                        new AmendOrder(
                                orderId,
                                today().plusDays(5),
                                null,
                                List.of(new CreateOrder.Line(RICE, "EA", new BigDecimal("6")))),
                        buyer()));

        assertThat(race.first()).isInstanceOf(UUID.class);
        assertThat(race.second())
                .isInstanceOfSatisfying(ProblemException.class, problem -> assertThat(problem.messageId())
                        .isEqualTo("m4.order.not_amendable"));
        assertThat(status(orderId)).isEqualTo("SUBMITTED");
        assertThat(allocations(orderId)).isOne();
    }

    @Test
    void aCancellationHeldOpenMakesTheDeliveryNoteWaitAndFail() throws Exception {
        OrderView order = flow.acceptedOrder();
        UUID noteId = flow.draftNote(order, SHOP);
        UUID orderId = order.orderId();

        Race race = race(
                () -> {
                    cancelOrder.handle(new CancelOrder(orderId, "CHANGED_MIND", null), buyer());
                    return "cancelled";
                },
                () -> issueNote.handle(new IssueDeliveryNote(noteId), seller()));

        assertThat(race.first()).isEqualTo("cancelled");
        assertThat(race.second())
                .isInstanceOfSatisfying(ProblemException.class, problem -> assertThat(problem.messageId())
                        .isEqualTo("m4.delivery.order_not_accepted"));
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(fulfilled(orderId)).isEqualByComparingTo("0");
    }

    @Test
    void aDeliveryNoteHeldOpenMakesTheCancellationWaitAndFail() throws Exception {
        OrderView order = flow.acceptedOrder();
        UUID noteId = flow.draftNote(order, SHOP);
        UUID orderId = order.orderId();

        Race race = race(() -> issueNote.handle(new IssueDeliveryNote(noteId), seller()), () -> {
            cancelOrder.handle(new CancelOrder(orderId, "CHANGED_MIND", null), buyer());
            return "cancelled";
        });

        assertThat(race.first()).isInstanceOf(String.class).isNotEqualTo("cancelled");
        assertThat(race.second())
                .isInstanceOfSatisfying(ProblemException.class, problem -> assertThat(problem.messageId())
                        .isEqualTo("m4.order.dispatched"));
        assertThat(status(orderId)).isEqualTo("SUBMITTED");
        assertThat(fulfilled(orderId)).isPositive();
    }

    /**
     * Runs {@code first} in a transaction held open for {@link #HOLD_MILLIS} after it returns, and
     * {@code second} on another thread once {@code first} has returned (so it holds its locks).
     * Each side's answer is its result or the exception it threw.
     */
    private Race race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        CountDownLatch acted = new CountDownLatch(1);
        CompletableFuture<Object> firstSide = CompletableFuture.supplyAsync(() -> {
            try {
                return new TransactionTemplate(transactions).execute(status -> {
                    Object result = first.get();
                    acted.countDown();
                    hold();
                    return result;
                });
            } catch (RuntimeException e) {
                acted.countDown();
                return e;
            }
        });
        assertThat(acted.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Object> secondSide = CompletableFuture.supplyAsync(() -> {
            try {
                return second.get();
            } catch (RuntimeException e) {
                return e;
            }
        });
        return new Race(firstSide.get(60, TimeUnit.SECONDS), secondSide.get(60, TimeUnit.SECONDS));
    }

    private static void hold() {
        try {
            Thread.sleep(HOLD_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String status(UUID orderId) {
        return superuserJdbc()
                .queryForObject("select status from kernel.document where document_id = ?", String.class, orderId);
    }

    private int allocations(UUID orderId) {
        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from trading.order_allocation where order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private BigDecimal fulfilled(UUID orderId) {
        return superuserJdbc()
                .queryForObject(
                        "select coalesce(sum(fulfilled_qty), 0) from trading.order_allocation_line where order_id = ?",
                        BigDecimal.class,
                        orderId);
    }

    private record Race(Object first, Object second) {}
}
