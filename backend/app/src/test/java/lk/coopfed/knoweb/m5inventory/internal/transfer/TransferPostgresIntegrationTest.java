package lk.coopfed.knoweb.m5inventory.internal.transfer;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReceiveTransfer;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TransferIssued;
import lk.coopfed.knoweb.m5inventory.api.TransferReceived;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.TransferView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The internal transfer (25A M5-09 at demo scope; doc 25 flow 6.6): a society's warehouse sends
 * stock to one of its shops; the stock leaves the warehouse as TRANSFER_OUT, is in transit, and
 * the shop's own session receives it as TRANSFER_IN at the cost it left with. Each side writes
 * only its own rows (PR #148): the shop session writes the receipt and its movements at the shop
 * and nothing at the warehouse. Every guard with nothing committed.
 */
class TransferPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67a-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e67a-0000-7000-8000-000000000003");

    @Autowired
    IssueTransferHandler issue;

    @Autowired
    ReceiveTransferHandler receive;

    @Autowired
    InventoryQueries queries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID shop;
    private UUID otherShop;
    private UUID batch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5XF", "MPCS");
        warehouse = fixture.location(MPCS, "WAREHOUSE");
        shop = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(MPCS, "SHOP");
        UUID sku = fixture.sku(MPCS, "RICE5");
        batch = fixture.batch(sku, MPCS, "R1", LocalDate.of(2027, 3, 31));
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        warehouse,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("50"),
                                        new BigDecimal("1200"),
                                        null))),
                        scope));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void theWarehouseSendsAndTheShopReceivesEachWritingItsOwnRows() {
        UUID id = issue.handle(transfer(warehouse, shop, "20"), own(MPCS));

        assertThat(qty(warehouse)).isEqualByComparingTo("30");
        assertThat(queries.balances(shop, null, false, own(MPCS))).isEmpty();
        TransferView sent = queries.transfer(id, own(MPCS)).orElseThrow();
        assertThat(sent.status()).isEqualTo("IN_TRANSIT");
        assertThat(sent.lines()).singleElement().satisfies(l -> {
            assertThat(l.qty()).isEqualByComparingTo("20");
            assertThat(l.unitCost()).isEqualByComparingTo("1200");
        });
        assertThat(queries.movementsOf(id, own(MPCS))).singleElement().satisfies(m -> assertThat(m.movementType())
                .isEqualTo("TRANSFER_OUT"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_ISSUED");
        assertThat(events(TransferIssued.class)).singleElement().satisfies(e -> {
            assertThat(e.fromLocationId()).isEqualTo(warehouse);
            assertThat(e.toLocationId()).isEqualTo(shop);
        });

        // The shop's own session sees the transfer addressed to it, and not the warehouse's stock.
        ScopeContext shopSession = at(MPCS, shop);
        assertThat(queries.transfers(shop, shopSession)).singleElement().satisfies(t -> assertThat(t.status())
                .isEqualTo("IN_TRANSIT"));
        assertThat(queries.balances(warehouse, null, false, shopSession)).isEmpty();

        kernel.reset();
        receive.handle(new ReceiveTransfer(id), shopSession);

        assertThat(qty(shop)).isEqualByComparingTo("20");
        assertThat(qty(warehouse)).isEqualByComparingTo("30");
        assertThat(queries.balances(shop, null, false, shopSession))
                .singleElement()
                .satisfies(l -> assertThat(l.unitCost()).isEqualByComparingTo("1200"));
        assertThat(queries.entityAverageCost(heldSku(), own(MPCS))).hasValueSatisfying(c -> {
            assertThat(c.avgCost()).isEqualByComparingTo("1200");
            assertThat(c.qtyOnHand()).isEqualByComparingTo("50");
        });
        assertThat(queries.transfer(id, own(MPCS)).orElseThrow()).satisfies(t -> {
            assertThat(t.status()).isEqualTo("RECEIVED");
            assertThat(t.receivedBy()).isEqualTo(InventoryFixture.USER);
        });
        // The warehouse sees it received, through the receipt row the shop wrote.
        assertThat(queries.transfer(id, at(MPCS, warehouse)).orElseThrow().status())
                .isEqualTo("RECEIVED");
        // Every row the shop's session wrote is the shop's: the receipt and the TRANSFER_IN.
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select location_id from inventory.transfer_receipt where transfer_id = ?",
                                UUID.class,
                                id))
                .isEqualTo(shop);
        assertThat(superuserJdbc()
                        .queryForList(
                                "select distinct location_id from inventory.stock_movement where document_id = ?"
                                        + " and movement_type = 'TRANSFER_IN'",
                                UUID.class,
                                id))
                .containsExactly(shop);
        // wave 2, M5-16: the same person issued and received it; allowed, and flagged.
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "TRANSFER_RECEIVED", "TRANSFER_SELF_RECEIVED");
        assertThat(events(TransferReceived.class)).singleElement().satisfies(e -> assertThat(e.toLocationId())
                .isEqualTo(shop));
    }

    @Test
    void aTransferReceivedByAnotherPersonIsNotFlagged() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        kernel.reset();

        receive.handle(new ReceiveTransfer(id), own(MPCS, Ids.next()));

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("TRANSFER_RECEIVED")
                .doesNotContain("TRANSFER_SELF_RECEIVED");
    }

    @Test
    void issueGuards() {
        UUID elsewhere = fixture.location(OTHER, "SHOP");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "1"), viewOnly()), "m5.scope.own_required");
        assertProblem(() -> issue.handle(transfer(elsewhere, shop, "1"), own(MPCS)), "m5.location.not_in_scope");
        assertProblem(() -> issue.handle(transfer(warehouse, warehouse, "1"), own(MPCS)), "m5.transfer.same_location");
        assertProblem(
                () -> issue.handle(transfer(warehouse, elsewhere, "1"), own(MPCS)), "m5.transfer.destination_invalid");
        // A session held to the warehouse cannot see the shop: issuing is entity-wide work.
        assertProblem(
                () -> issue.handle(transfer(warehouse, shop, "1"), at(MPCS, warehouse)),
                "m5.transfer.destination_invalid");
        assertProblem(
                () -> issue.handle(new IssueTransfer(warehouse, shop, List.of()), own(MPCS)),
                "m5.transfer.lines_required");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "0"), own(MPCS)), "m5.transfer.line_invalid");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "1.0001"), own(MPCS)), "m5.transfer.line_invalid");
        assertProblem(
                () -> issue.handle(
                        new IssueTransfer(warehouse, shop, List.of(new IssueTransfer.Line(Ids.next(), BigDecimal.ONE))),
                        own(MPCS)),
                "m5.batch.not_found");
        assertProblem(() -> issue.handle(transfer(warehouse, shop, "51"), own(MPCS)), "m5.transfer.insufficient_stock");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(qty(warehouse)).isEqualByComparingTo("50");
        assertThat(queries.transfers(warehouse, own(MPCS))).isEmpty();
    }

    @Test
    void receiveGuards() {
        UUID id = issue.handle(transfer(warehouse, shop, "5"), own(MPCS));
        kernel.reset();

        assertProblem(() -> receive.handle(new ReceiveTransfer(id), viewOnly()), "m5.scope.own_required");
        assertProblem(() -> receive.handle(new ReceiveTransfer(Ids.next()), at(MPCS, shop)), "m5.transfer.not_found");
        // Another shop of the society does not see it at all; the source sees it but does not receive it.
        assertProblem(() -> receive.handle(new ReceiveTransfer(id), at(MPCS, otherShop)), "m5.transfer.not_found");
        assertProblem(
                () -> receive.handle(new ReceiveTransfer(id), at(MPCS, warehouse)), "m5.transfer.not_destination");
        assertThat(kernel.committedAudit()).isEmpty();

        receive.handle(new ReceiveTransfer(id), at(MPCS, shop));
        kernel.reset();
        assertProblem(() -> receive.handle(new ReceiveTransfer(id), at(MPCS, shop)), "m5.transfer.already_received");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(qty(shop)).isEqualByComparingTo("5");
    }

    // ---- helpers --------------------------------------------------------------------------

    private UUID heldSku() {
        return queries.balances(warehouse, null, true, own(MPCS)).get(0).skuId();
    }

    private IssueTransfer transfer(UUID from, UUID to, String qty) {
        return new IssueTransfer(from, to, List.of(new IssueTransfer.Line(batch, new BigDecimal(qty))));
    }

    private BigDecimal qty(UUID location) {
        return queries.balances(location, null, true, own(MPCS)).stream()
                .map(LotBalance::qtyOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ScopeContext viewOnly() {
        lk.coopfed.knoweb.kernel.api.Scope scope = new lk.coopfed.knoweb.kernel.api.Scope(MPCS, null);
        return new ScopeContext(
                InventoryFixture.USER,
                null,
                MPCS,
                List.of(scope),
                scope,
                lk.coopfed.knoweb.kernel.api.PolicyClass.FEDERATION_VIEW,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }
}
