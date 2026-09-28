package lk.coopfed.knoweb.m4trading.internal.order;

import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RELATIONSHIP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.AmendRelationshipTerms;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.AmendOrder;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.OrderAmended;
import lk.coopfed.knoweb.m4trading.api.OrderCancelled;
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

/**
 * AmendOrder (24A section 6): the buyer's next version of an undecided order, the amended one
 * cancelled, what is audited and published; an accepted order refused. And the credit limit
 * changed by M1 (AmendRelationshipTerms, a new row from today) while an order is open: the order
 * is still accepted, under the row in force (OrderGuards).
 */
class AmendOrderPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    CreateOrderHandler create;

    @Autowired
    SubmitOrderHandler submit;

    @Autowired
    AcceptOrderHandler accept;

    @Autowired
    AmendOrderHandler amend;

    @Autowired
    Handles<AmendRelationshipTerms, UUID> amendTerms;

    @Autowired
    OrderQueries orders;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    private UUID submitted() {
        UUID orderId = create.handle(
                new CreateOrder(
                        SELLER,
                        today().plusDays(3),
                        null,
                        List.of(
                                new CreateOrder.Line(RICE, "EA", new BigDecimal("10")),
                                new CreateOrder.Line(DHAL, "EA", new BigDecimal("4")))),
                buyer());
        submit.handle(new SubmitOrder(orderId), buyer());
        return orderId;
    }

    private static AmendOrder riceOnly(UUID orderId, String qty) {
        return new AmendOrder(
                orderId,
                today().plusDays(5),
                "less rice",
                List.of(new CreateOrder.Line(RICE, "EA", new BigDecimal(qty))));
    }

    @Test
    void theBuyerAmendsASubmittedOrderIntoItsNextVersionWhichTheSellerAcceptsAfresh() {
        UUID first = submitted();
        kernel.reset();

        UUID next = amend.handle(riceOnly(first, "6"), buyer());

        OrderView amended = orders.getOrder(next, seller()).orElseThrow();
        assertThat(amended.status()).isEqualTo("SUBMITTED");
        assertThat(amended.docNumberDisplay()).isEqualTo("D4B-ORD-0000002");
        assertThat(amended.version()).isEqualTo(2);
        assertThat(amended.amendsOrderId()).isEqualTo(first);
        assertThat(amended.requestedEta()).isEqualTo(today().plusDays(5));
        assertThat(amended.lines()).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo(RICE);
            assertThat(line.requestedQty()).isEqualByComparingTo("6");
        });
        assertThat(amended.netAmount()).isEqualByComparingTo("720.00");
        OrderView old = orders.getOrder(first, buyer()).orElseThrow();
        assertThat(old.status()).isEqualTo("CANCELLED");
        assertThat(old.amendedByOrderId()).isEqualTo(next);

        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .contains("DOCUMENT_ISSUED", "ORDER_AMENDED");
        assertThat(events(OrderAmended.class)).singleElement().satisfies(event -> {
            assertThat(event.orderId()).isEqualTo(next);
            assertThat(event.amendsOrderId()).isEqualTo(first);
            assertThat(event.version()).isEqualTo(2);
            assertThat(event.status()).isEqualTo("SUBMITTED");
            assertThat(event.lines()).hasSize(1);
        });
        assertThat(events(OrderCancelled.class)).singleElement().satisfies(event -> {
            assertThat(event.orderId()).isEqualTo(first);
            assertThat(event.reasonCode()).isEqualTo(AmendOrderHandler.AMENDED_REASON);
        });
        assertThat(events(OrderSubmitted.class)).singleElement().satisfies(event -> assertThat(event.orderId())
                .isEqualTo(next));

        accept.handle(new AcceptOrder(next, today().plusDays(5), List.of()), seller());
        assertThat(orders.getOrder(next, seller()).orElseThrow().status()).isEqualTo("ACCEPTED");
    }

    @Test
    void aDraftIsAmendedIntoANewDraft() {
        UUID draft = create.handle(
                new CreateOrder(SELLER, null, null, List.of(new CreateOrder.Line(RICE, "EA", BigDecimal.TEN))),
                buyer());
        kernel.reset();

        UUID next = amend.handle(riceOnly(draft, "12"), buyer());

        OrderView amended = orders.getOrder(next, buyer()).orElseThrow();
        assertThat(amended.status()).isEqualTo("DRAFT");
        assertThat(amended.docNumberDisplay()).isNull();
        assertThat(orders.getOrder(draft, buyer()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(events(OrderAmended.class)).singleElement().satisfies(event -> assertThat(event.status())
                .isEqualTo("DRAFT"));
        assertThat(events(OrderSubmitted.class)).isEmpty();
    }

    @Test
    void anAcceptedOrderAndEveryBrokenGuardAreRefusedAndNothingIsCommitted() {
        UUID first = submitted();
        kernel.reset();

        refused(() -> amend.handle(riceOnly(first, "6"), seller()), "m4.order.not_buyer");
        refused(() -> amend.handle(new AmendOrder(first, null, null, List.of()), buyer()), "m4.order.lines_required");
        refused(
                () -> amend.handle(
                        new AmendOrder(
                                first,
                                today().minusDays(1),
                                null,
                                List.of(new CreateOrder.Line(RICE, "EA", BigDecimal.ONE))),
                        buyer()),
                "m4.order.eta_past");
        refused(() -> amend.handle(riceOnly(first, "0"), buyer()), "m4.order.qty_not_positive");

        accept.handle(new AcceptOrder(first, today().plusDays(3), List.of()), seller());
        kernel.reset();
        refused(() -> amend.handle(riceOnly(first, "6"), buyer()), "m4.order.not_amendable");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aCreditLimitChangedWhileAnOrderIsOpenLeavesTheOrderAcceptable() {
        UUID open = submitted();

        // M1 closes the row the order names and opens the next one from today (21A section 6.1). A
        // limit change closes it the same way; the terms are amended here because the test user
        // holds no bil.creditlimit.change (the resolver checks it; M1's own tests cover that guard).
        UUID nextRow = amendTerms.handle(
                new AmendRelationshipTerms(
                        RELATIONSHIP, today(), null, null, 45, null, null, null, "REVIEW", null),
                sellerWithFreshSecondFactor());
        assertThat(nextRow).isNotEqualTo(RELATIONSHIP);
        kernel.reset();

        accept.handle(new AcceptOrder(open, today().plusDays(3), List.of()), seller());

        assertThat(orders.getOrder(open, seller()).orElseThrow().status()).isEqualTo("ACCEPTED");
    }

    private static ScopeContext sellerWithFreshSecondFactor() {
        Scope scope = new Scope(SELLER, null);
        return new ScopeContext(
                SELLER_USER,
                null,
                SELLER,
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
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
