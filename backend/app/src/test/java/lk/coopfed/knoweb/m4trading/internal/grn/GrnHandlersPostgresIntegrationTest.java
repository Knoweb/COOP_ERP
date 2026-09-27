package lk.coopfed.knoweb.m4trading.internal.grn;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE_PRICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
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
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.DiscrepancyRaised;
import lk.coopfed.knoweb.m4trading.api.GrnCaptured;
import lk.coopfed.knoweb.m4trading.api.GrnConfirmed;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** CaptureGrn and ConfirmGrn (24A sections 6 and 6.1): guards, the pivot, M2's batches, the discrepancy, audit and events. */
@Import(TradingFlow.class)
class GrnHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    GrnQueries grns;

    @Autowired
    DeliveryQueries deliveries;

    private UUID noteId;
    private UUID dropId;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        noteId = flow.dispatchedNote(SHOP);
        dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theShopReceivesInFullAndConfirmingRegistersBatchesAndClosesTheDrop() {
        UUID grnId = capture.handle(
                new CaptureGrn(
                        dropId, SHOP, null, List.of(line(RICE, "10", "0", "B2411A"), line(DHAL, "4", "0", null))),
                buyerAt(SHOP));
        assertThat(events(GrnCaptured.class)).singleElement().satisfies(event -> assertThat(event.lineCount())
                .isEqualTo(2));
        kernel.reset();

        String number = confirm.handle(new ConfirmGrn(grnId), buyerAt(SHOP));

        assertThat(number).isEqualTo("D4B-GRN-0000001"); // no LOCATION series registered in the fixture
        GrnView grn = grns.getGrn(grnId, buyerAt(SHOP)).orElseThrow();
        assertThat(grn.status()).isEqualTo("CONFIRMED");
        assertThat(grn.discrepancyId()).isNull();
        assertThat(grn.lines()).allSatisfy(line -> {
            assertThat(line.batchId()).isNotNull();
            assertThat(line.unitCost()).isNotNull();
        });
        assertThat(grn.lines().get(0).unitCost()).isEqualByComparingTo(RICE_PRICE);
        // The receiver owns the batches now (AGENTS.md idea 2); an item not batch-tracked takes a synthetic batch (doc
        // 22 section 3.7).
        assertThat(superuserJdbc()
                        .queryForList(
                                "select owner_entity_id from catalogue.batch where origin_document_id = ?",
                                UUID.class,
                                grnId))
                .hasSize(2)
                .containsOnly(BUYER);

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DOCUMENT_ISSUED", "BATCH_REGISTERED", "GRN_CONFIRMED")
                .doesNotContain("DISCREPANCY_RAISED");
        assertThat(events(BatchRegistered.class)).hasSize(2);
        assertThat(events(GrnConfirmed.class)).singleElement().satisfies(event -> {
            assertThat(event.docNumberDisplay()).isEqualTo(number);
            assertThat(event.receiverLocationId()).isEqualTo(SHOP);
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.variance()).isFalse();
            assertThat(event.lines())
                    .allSatisfy(line -> assertThat(line.batchId()).isNotNull());
            assertThat(event.lines().get(0).batchNo()).isEqualTo("S-" + number + "-1");
        });
        assertThat(events(DiscrepancyRaised.class)).isEmpty();

        // The seller sees its drop received and the note closed (CR-24A-1 item 3).
        assertThat(deliveries.getDeliveryNote(noteId, seller()).orElseThrow().status())
                .isEqualTo("CLOSED");
    }

    @Test
    void aShortAndDamagedCountRaisesAMixedDiscrepancyAtTheShop() {
        // 8 of 10 rice, one damaged; the dhal is not counted at all (short by 4).
        UUID grnId =
                capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "8", "1", null))), buyerAt(SHOP));
        kernel.reset();

        confirm.handle(new ConfirmGrn(grnId), buyerAt(SHOP));

        GrnView grn = grns.getGrn(grnId, buyer()).orElseThrow();
        assertThat(grn.discrepancyId()).isNotNull();
        assertThat(grn.lines().get(1).receivedQty()).isEqualByComparingTo("0");
        assertThat(grn.lines().get(1).batchId()).isNull(); // nothing received, no batch
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select location_id from kernel.document where document_id = ?",
                                UUID.class,
                                grn.discrepancyId()))
                .isEqualTo(SHOP);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select link_type from kernel.document_link where from_document_id = ?",
                                String.class,
                                grn.discrepancyId()))
                .isEqualTo("DISPUTES");
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("GRN_CONFIRMED", "DISCREPANCY_RAISED");
        assertThat(events(DiscrepancyRaised.class)).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("MIXED");
            assertThat(event.receiverLocationId()).isEqualTo(SHOP);
            assertThat(event.lines()).hasSize(2);
            assertThat(event.lines().get(0).varianceQty()).isEqualByComparingTo("-2");
            assertThat(event.lines().get(1).varianceQty()).isEqualByComparingTo("-4");
        });
        assertThat(events(GrnConfirmed.class)).singleElement().satisfies(event -> assertThat(event.variance())
                .isTrue());
    }

    @Test
    void theGuardsOfCaptureGrn() {
        List<CaptureGrn.Line> full = List.of(line(RICE, "10", "0", null));
        refused(
                () -> capture.handle(new CaptureGrn(dropId, WAREHOUSE, null, full), buyer()),
                "m4.grn.location_mismatch");
        refused(() -> capture.handle(new CaptureGrn(dropId, SHOP, null, full), buyerAt(WAREHOUSE)), "scope.invalid");
        refused(() -> capture.handle(new CaptureGrn(dropId, SHOP, null, full), seller()), "m4.grn.location_unknown");
        refused(
                () -> capture.handle(new CaptureGrn(UUID.randomUUID(), SHOP, null, full), buyer()),
                "m4.grn.drop_unknown");
        refused(() -> capture.handle(new CaptureGrn(dropId, SHOP, null, List.of()), buyer()), "m4.grn.lines_required");
        refused(
                () -> capture.handle(
                        new CaptureGrn(dropId, SHOP, null, List.of(line(TradingFixture.DRAFT_SKU, "1", "0", null))),
                        buyer()),
                "m4.grn.sku_not_on_drop");
        refused(
                () -> capture.handle(
                        new CaptureGrn(
                                dropId, SHOP, null, List.of(line(RICE, "1", "0", null), line(RICE, "1", "0", null))),
                        buyer()),
                "m4.grn.line_duplicate");
        refused(
                () -> capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "2", "3", null))), buyer()),
                "m4.grn.line_quantities");
        assertThat(kernel.committedAudit()).isEmpty();

        capture.handle(new CaptureGrn(dropId, SHOP, null, full), buyer());
        kernel.reset();
        refused(
                () -> capture.handle(new CaptureGrn(dropId, SHOP, null, full), buyer()),
                "m4.grn.drop_already_captured");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theGuardsOfConfirmGrn() {
        UUID grnId = capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10", "0", null))), buyer());
        kernel.reset();

        refused(() -> confirm.handle(new ConfirmGrn(grnId), seller()), "m4.grn.not_receiver");
        refused(() -> confirm.handle(new ConfirmGrn(UUID.randomUUID()), buyer()), "m4.grn.not_found");
        refused(
                () -> confirm.handle(new ConfirmGrn(grnId), ScopeContext.dev(UUID.randomUUID(), BUYER, WAREHOUSE)),
                "m4.grn.not_found"); // a session at the warehouse does not see the shop's GRN
        // An item with a printed MRP needs it keyed before the pivot.
        superuserJdbc().update("update catalogue.sku set has_printed_mrp = true where sku_id = ?", RICE);
        refused(() -> confirm.handle(new ConfirmGrn(grnId), buyer()), "m4.grn.mrp_required");
        superuserJdbc().update("update catalogue.sku set has_printed_mrp = false where sku_id = ?", RICE);
        assertThat(kernel.committedEvents()).isEmpty();

        confirm.handle(new ConfirmGrn(grnId), buyer());
        kernel.reset();
        refused(() -> confirm.handle(new ConfirmGrn(grnId), buyer()), "m4.grn.not_draft");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private static CaptureGrn.Line line(UUID sku, String received, String damaged, String batchNo) {
        return new CaptureGrn.Line(
                sku, "EA", new BigDecimal(received), new BigDecimal(damaged), batchNo, null, null, null);
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
