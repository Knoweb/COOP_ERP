package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.CreateDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.internal.delivery.CreateDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.delivery.DispatchDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.delivery.IssueDeliveryNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.order.AcceptOrderHandler;
import lk.coopfed.knoweb.m4trading.internal.order.CreateOrderHandler;
import lk.coopfed.knoweb.m4trading.internal.order.SubmitOrderHandler;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

/**
 * The steps of the trading flow the later tests start from, through the real handlers: an order of
 * 10 rice and 4 dhal, submitted and accepted; a delivery note of it to the buyer's shop, issued and
 * dispatched. Imported by the tests that need it.
 */
@TestComponent
public class TradingFlow {

    @Autowired
    CreateOrderHandler createOrder;

    @Autowired
    SubmitOrderHandler submitOrder;

    @Autowired
    AcceptOrderHandler acceptOrder;

    @Autowired
    CreateDeliveryNoteHandler createNote;

    @Autowired
    IssueDeliveryNoteHandler issueNote;

    @Autowired
    DispatchDeliveryNoteHandler dispatchNote;

    @Autowired
    OrderQueries orders;

    public OrderView acceptedOrder() {
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
        acceptOrder.handle(new AcceptOrder(orderId, today().plusDays(3), List.of()), seller());
        return orders.getOrder(orderId, seller()).orElseThrow();
    }

    /** A draft note carrying every line of the order in full to one drop at the ship-to shop. */
    public UUID draftNote(OrderView order, UUID shipTo) {
        return createNote.handle(
                new CreateDeliveryNote(
                        "WP-1234",
                        "Sunil",
                        null,
                        List.of(new CreateDeliveryNote.Drop(
                                shipTo,
                                BUYER,
                                order.lines().stream()
                                        .map(line ->
                                                new CreateDeliveryNote.Line(line.lineId(), line.allocatedQty(), null))
                                        .toList()))),
                seller());
    }

    /** An accepted order delivered in full to the ship-to shop, issued and dispatched. */
    public UUID dispatchedNote(UUID shipTo) {
        UUID noteId = draftNote(acceptedOrder(), shipTo);
        issueNote.handle(new IssueDeliveryNote(noteId), seller());
        dispatchNote.handle(new DispatchDeliveryNote(noteId, null, null, null), seller());
        return noteId;
    }
}
