package lk.coopfed.knoweb.m4trading.internal.transfer;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_WAREHOUSE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.WAREHOUSE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyerAt;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.api.ApproveTransferRequest;
import lk.coopfed.knoweb.m4trading.api.RejectTransferRequest;
import lk.coopfed.knoweb.m4trading.api.RequestTransfer;
import lk.coopfed.knoweb.m4trading.api.TransferRequestApproved;
import lk.coopfed.knoweb.m4trading.api.TransferRequestRejected;
import lk.coopfed.knoweb.m4trading.api.TransferRequested;
import lk.coopfed.knoweb.m4trading.query.TransferRequestQueries;
import lk.coopfed.knoweb.m4trading.query.TransferRequestView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Transfer requests (M4-10; doc 24 section 4.7): the shop asks its society's stores for stock; the
 * society approves (M5 issues the transfer, ConsumersPostgresIntegrationTest of m5inventory) or
 * rejects. Every guard, and what each handler audits and publishes. The shop session writes at the
 * shop only, the society's decision is a row of its own.
 */
class TransferRequestHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    RequestTransferHandler request;

    @Autowired
    ApproveTransferRequestHandler approve;

    @Autowired
    RejectTransferRequestHandler reject;

    @Autowired
    TransferRequestQueries requests;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        // The society's stores hold 20 rice and 5 dhal.
        lot(RICE, "20");
        lot(DHAL, "5");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    private UUID ask(String rice) {
        return request.handle(
                new RequestTransfer(
                        WAREHOUSE, null, "Weekend", List.of(new RequestTransfer.Line(RICE, new BigDecimal(rice)))),
                buyerAt(SHOP));
    }

    @Test
    void theShopAsksAndTheSocietyApproves() {
        UUID requestId = ask("12");

        TransferRequestView asked =
                requests.getRequest(requestId, buyerAt(SHOP)).orElseThrow();
        assertThat(asked.status()).isEqualTo("REQUESTED");
        assertThat(asked.fromLocationId()).isEqualTo(WAREHOUSE);
        assertThat(asked.toLocationId()).isEqualTo(SHOP);
        assertThat(asked.reason()).isEqualTo("Weekend");
        assertThat(asked.lines()).singleElement().satisfies(line -> assertThat(line.qty())
                .isEqualByComparingTo("12"));
        assertThat(requests.listRequests(buyer()))
                .extracting(TransferRequestView::requestId)
                .containsExactly(requestId);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TRANSFER_REQUESTED");
        assertThat(events(TransferRequested.class)).singleElement().satisfies(event -> {
            assertThat(event.ownerEntityId()).isEqualTo(BUYER);
            assertThat(event.toLocationId()).isEqualTo(SHOP);
        });

        kernel.reset();
        refused(() -> approve.handle(new ApproveTransferRequest(requestId), buyerAt(SHOP)), "scope.invalid");
        refused(() -> approve.handle(new ApproveTransferRequest(requestId), seller()), "m4.transfer.request_not_found");
        assertThat(kernel.committedEvents()).isEmpty();

        approve.handle(new ApproveTransferRequest(requestId), buyer());

        TransferRequestView approved =
                requests.getRequest(requestId, buyerAt(SHOP)).orElseThrow();
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.decidedAt()).isNotNull();
        // M5 has not issued the transfer yet (its consumer runs on the event).
        assertThat(approved.transferId()).isNull();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TRANSFER_APPROVED");
        assertThat(events(TransferRequestApproved.class)).singleElement().satisfies(event -> {
            assertThat(event.fromLocationId()).isEqualTo(WAREHOUSE);
            assertThat(event.toLocationId()).isEqualTo(SHOP);
            assertThat(event.lines()).singleElement().satisfies(line -> assertThat(line.skuId())
                    .isEqualTo(RICE));
        });

        kernel.reset();
        refused(() -> approve.handle(new ApproveTransferRequest(requestId), buyer()), "m4.transfer.decided");
        refused(() -> reject.handle(new RejectTransferRequest(requestId, "no"), buyer()), "m4.transfer.decided");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theShopMayLeaveTheSourceToTheSociety() {
        // A shop session sees no other location of its society, so it asks without naming one.
        UUID requestId = request.handle(
                new RequestTransfer(null, null, null, List.of(new RequestTransfer.Line(DHAL, BigDecimal.TWO))),
                buyerAt(SHOP));
        assertThat(requests.getRequest(requestId, buyerAt(SHOP)).orElseThrow().fromLocationId())
                .isNull();

        refused(() -> approve.handle(new ApproveTransferRequest(requestId), buyer()), "m4.transfer.source_required");
        refused(
                () -> approve.handle(new ApproveTransferRequest(requestId, SHOP), buyer()),
                "m4.transfer.same_location");
        kernel.reset();

        approve.handle(new ApproveTransferRequest(requestId, WAREHOUSE), buyer());

        assertThat(requests.getRequest(requestId, buyerAt(SHOP)).orElseThrow().fromLocationId())
                .isEqualTo(WAREHOUSE);
        assertThat(events(TransferRequestApproved.class))
                .singleElement()
                .satisfies(event -> assertThat(event.fromLocationId()).isEqualTo(WAREHOUSE));
    }

    @Test
    void theRequestGuards() {
        refused(
                () -> request.handle(
                        new RequestTransfer(
                                WAREHOUSE, WAREHOUSE, null, List.of(new RequestTransfer.Line(RICE, BigDecimal.ONE))),
                        buyerAt(SHOP)),
                "m4.transfer.location_not_in_scope");
        refused(
                () -> request.handle(
                        new RequestTransfer(SHOP, null, null, List.of(new RequestTransfer.Line(RICE, BigDecimal.ONE))),
                        buyerAt(SHOP)),
                "m4.transfer.same_location");
        refused(
                () -> request.handle(new RequestTransfer(WAREHOUSE, null, null, List.of()), buyerAt(SHOP)),
                "m4.transfer.lines_required");
        refused(
                () -> request.handle(
                        new RequestTransfer(
                                WAREHOUSE,
                                null,
                                null,
                                List.of(new RequestTransfer.Line(UUID.randomUUID(), BigDecimal.ONE))),
                        buyerAt(SHOP)),
                "m4.transfer.sku_unknown");
        refused(
                () -> request.handle(
                        new RequestTransfer(
                                WAREHOUSE,
                                null,
                                null,
                                List.of(
                                        new RequestTransfer.Line(RICE, BigDecimal.ONE),
                                        new RequestTransfer.Line(RICE, BigDecimal.ONE))),
                        buyerAt(SHOP)),
                "m4.transfer.sku_duplicate");
        refused(() -> ask("0"), "m4.transfer.qty_invalid");
        refused(() -> ask("1.2345"), "m4.transfer.qty_invalid");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theSocietyRefusesWhatTheStoresDoNotHoldAndRejectsWithAReason() {
        UUID tooMuch = ask("21");
        refused(() -> approve.handle(new ApproveTransferRequest(tooMuch), buyer()), "m4.transfer.insufficient_stock");

        // A source that is not the society's own location is caught at the decision.
        UUID elsewhere = request.handle(
                new RequestTransfer(
                        SELLER_WAREHOUSE, null, null, List.of(new RequestTransfer.Line(RICE, BigDecimal.ONE))),
                buyerAt(SHOP));
        refused(() -> approve.handle(new ApproveTransferRequest(elsewhere), buyer()), "m4.transfer.location_invalid");

        kernel.reset();
        refused(() -> reject.handle(new RejectTransferRequest(tooMuch, " "), buyer()), "request.field.required");
        reject.handle(new RejectTransferRequest(tooMuch, "Keep it for the members' order"), buyer());

        TransferRequestView rejected =
                requests.getRequest(tooMuch, buyerAt(SHOP)).orElseThrow();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectReason()).isEqualTo("Keep it for the members' order");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("TRANSFER_REJECTED");
        assertThat(events(TransferRequestRejected.class)).singleElement().satisfies(event -> assertThat(event.reason())
                .isEqualTo("Keep it for the members' order"));
    }

    private void lot(UUID sku, String qty) {
        superuserJdbc()
                .update(
                        """
                        insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,
                            qty_on_hand, unit_cost, received_at)
                        values (?, ?, ?, ?, ?, ?, 90, now())
                        """,
                        UUID.randomUUID(),
                        BUYER,
                        WAREHOUSE,
                        UUID.randomUUID(),
                        sku,
                        new BigDecimal(qty));
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
