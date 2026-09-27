package lk.coopfed.knoweb.m4trading.internal.delivery;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CreateDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteCreated;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteDispatched;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.DeliveryView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** CreateDeliveryNote, IssueDeliveryNote and Dispatch (24A section 6): guards, rows, fulfilment, audit and events. */
@Import(TradingFlow.class)
class DeliveryHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CreateDeliveryNoteHandler create;

    @Autowired
    IssueDeliveryNoteHandler issue;

    @Autowired
    DispatchDeliveryNoteHandler dispatch;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    OrderQueries orders;

    private OrderView order;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        order = flow.acceptedOrder();
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theSellerDraftsIssuesAndDispatchesANoteAndTheOrderIsFulfilled() {
        UUID noteId = flow.draftNote(order, SHOP);
        assertThat(events(DeliveryNoteCreated.class)).singleElement().satisfies(event -> {
            assertThat(event.orderIds()).containsExactly(order.orderId());
            assertThat(event.drops()).singleElement().satisfies(drop -> assertThat(drop.lines()).hasSize(2));
        });
        DeliveryView draft = deliveries.getDeliveryNote(noteId, seller()).orElseThrow();
        assertThat(draft.status()).isEqualTo("DRAFT");
        // The buyer does not see a draft note in its list.
        assertThat(deliveries.listDeliveryNotes(OrderQueries.Role.BUYER, buyer())).isEmpty();
        kernel.reset();

        String number = issue.handle(new IssueDeliveryNote(noteId), seller());
        assertThat(number).isEqualTo("D4S-DN-0000001");
        OrderView fulfilled = orders.getOrder(order.orderId(), buyer()).orElseThrow();
        assertThat(fulfilled.status()).isEqualTo("FULFILLED");
        assertThat(fulfilled.lines()).allSatisfy(line -> assertThat(line.fulfilledQty())
                .isEqualByComparingTo(line.allocatedQty()));
        assertThat(events(DeliveryNoteIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.docNumberDisplay()).isEqualTo(number);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.drops().get(0).shipToLocationId()).isEqualTo(SHOP);
        });
        kernel.reset();

        dispatch.handle(new DispatchDeliveryNote(noteId, "WP-9999", null, null), seller());
        DeliveryView inTransit = deliveries.getDeliveryNote(noteId, buyer()).orElseThrow();
        assertThat(inTransit.status()).isEqualTo("IN_TRANSIT");
        assertThat(inTransit.vehicleRef()).isEqualTo("WP-9999");
        assertThat(inTransit.driverName()).isEqualTo("Sunil");
        assertThat(inTransit.dispatchedAt()).isNotNull();
        assertThat(inTransit.drops()).singleElement().satisfies(drop -> assertThat(drop.status()).isEqualTo("PLANNED"));
        assertThat(deliveries.listDeliveryNotes(OrderQueries.Role.BUYER, buyer())).hasSize(1);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DN_DISPATCHED");
        assertThat(events(DeliveryNoteDispatched.class)).singleElement().satisfies(event -> assertThat(event.vehicleRef())
                .isEqualTo("WP-9999"));
    }

    @Test
    void theGuardsOfCreateDeliveryNote() {
        UUID riceLine = order.lines().get(0).lineId();
        refused(() -> create.handle(new CreateDeliveryNote(null, null, null, List.of()), seller()), "m4.delivery.drops_required");
        refused(() -> create.handle(note(SHOP, BUYER, List.of()), seller()), "m4.delivery.lines_required");
        refused(
                () -> create.handle(
                        new CreateDeliveryNote(
                                null,
                                null,
                                null,
                                List.of(drop(SHOP, BUYER, riceLine, "1"), drop(SHOP, STRANGER, riceLine, "1"))),
                        seller()),
                "m4.delivery.one_buyer");
        refused(
                () -> create.handle(note(SHOP, BUYER, List.of(line(UUID.randomUUID(), "1"))), seller()),
                "m4.delivery.order_line_unknown");
        refused(
                () -> create.handle(note(SHOP, STRANGER, List.of(line(riceLine, "1"))), seller()),
                "m4.delivery.bill_to_mismatch");
        refused(() -> create.handle(note(SHOP, BUYER, List.of(line(riceLine, "0"))), seller()), "m4.delivery.qty_not_positive");
        refused(
                () -> create.handle(note(SHOP, BUYER, List.of(line(riceLine, "6"), line(riceLine, "5"))), seller()),
                "m4.delivery.exceeds_allocation");
        // The buyer is not the seller of its own order.
        refused(() -> create.handle(note(SHOP, BUYER, List.of(line(riceLine, "1"))), buyer()), "m4.delivery.order_not_accepted");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void issuanceRechecksWhatIsLeftToDispatchAndDispatchNeedsAnIssuedNoteWithAVehicle() {
        UUID first = flow.draftNote(order, SHOP);
        UUID second = flow.draftNote(order, SHOP);
        refused(() -> dispatch.handle(new DispatchDeliveryNote(first, "WP-1", null, null), seller()), "m4.delivery.not_issued");
        issue.handle(new IssueDeliveryNote(first), seller());
        kernel.reset();

        refused(() -> issue.handle(new IssueDeliveryNote(second), seller()), "m4.delivery.exceeds_allocation");
        refused(() -> issue.handle(new IssueDeliveryNote(first), seller()), "m4.delivery.not_draft");
        refused(() -> issue.handle(new IssueDeliveryNote(first), buyer()), "m4.delivery.not_seller");
        refused(() -> issue.handle(new IssueDeliveryNote(UUID.randomUUID()), seller()), "m4.delivery.not_found");
        superuserJdbc().update("update trading.doc_delivery set vehicle_ref = null where document_id = ?", first);
        refused(() -> dispatch.handle(new DispatchDeliveryNote(first, " ", null, null), seller()), "m4.delivery.vehicle_required");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private static CreateDeliveryNote note(UUID shipTo, UUID billTo, List<CreateDeliveryNote.Line> lines) {
        return new CreateDeliveryNote(null, null, null, List.of(new CreateDeliveryNote.Drop(shipTo, billTo, lines)));
    }

    private static CreateDeliveryNote.Drop drop(UUID shipTo, UUID billTo, UUID orderLine, String qty) {
        return new CreateDeliveryNote.Drop(shipTo, billTo, List.of(line(orderLine, qty)));
    }

    private static CreateDeliveryNote.Line line(UUID orderLine, String qty) {
        return new CreateDeliveryNote.Line(orderLine, new BigDecimal(qty), null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call)
                .isInstanceOf(ProblemException.class)
                .satisfies(error -> assertThat(((ProblemException) error).messageId()).isEqualTo(messageId));
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
