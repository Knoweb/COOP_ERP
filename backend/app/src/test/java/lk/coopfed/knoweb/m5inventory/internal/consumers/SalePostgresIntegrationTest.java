package lk.coopfed.knoweb.m5inventory.internal.consumers;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.TillFactNotApplied;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The sale deductions of a till receipt (25A section 6.2) and their flags: a missing batch
 * resolved FEFO at the shop, a line with nothing to deduct from recorded for a person, the
 * guard with nothing committed. The whole path from the till is TillSaleEndToEndIntegrationTest.
 */
class SalePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e6a2-0000-7000-8000-000000000002");

    @Autowired
    ApplySaleHandler sale;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    InventoryQueries queries;

    @Autowired
    TillFactNotAppliedConsumer tillFacts;

    @Autowired
    ObjectMapper mapper;

    private InventoryFixture fixture;
    private UUID shop;
    private UUID sku;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        shop = fixture.location(MPCS, "SHOP");
        sku = fixture.sku(MPCS, "SUGAR1");
        UUID batch = fixture.batch(sku, MPCS, "S1", LocalDate.of(2027, 1, 31));
        outer.run(
                own(MPCS),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        shop,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("5"),
                                        new BigDecimal("250"),
                                        null))),
                        own(MPCS)));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void aLineWithoutItsBatchIsSoldFefoAndALineWithNothingToDeductIsFlagged() {
        UUID unknownItem = fixture.sku(MPCS, "NOLOT");
        UUID receipt = Ids.next();

        int posted = sale.handle(
                new ApplySale(
                        receipt,
                        shop,
                        Instant.now(),
                        List.of(
                                new ApplySale.Line(null, 1, sku, null, new BigDecimal("2")),
                                new ApplySale.Line(null, 2, unknownItem, null, BigDecimal.ONE))),
                at(shop));

        assertThat(posted).isEqualTo(1);
        assertThat(queries.balances(shop, sku, true, own(MPCS)))
                .extracting(LotBalance::qtyOnHand)
                .singleElement()
                .satisfies(q -> assertThat(q).isEqualByComparingTo("3"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "STOCK_SOLD", "SALE_LINE_UNRESOLVED")
                .doesNotContain("SALE_BATCH_SUBSTITUTED", "SALE_LINE_SKIPPED");

        // The same receipt again moves nothing.
        kernel.reset();
        assertThat(sale.handle(
                        new ApplySale(
                                receipt,
                                shop,
                                Instant.now(),
                                List.of(new ApplySale.Line(null, 1, sku, null, new BigDecimal("2")))),
                        at(shop)))
                .isZero();
    }

    /**
     * Wave 2, M6-06: a batch the till named that the catalogue does not know is deducted from the
     * shop's first lot and leaves a REVIEW trace; a line of no quantity is not deducted and leaves
     * one too; a line with no batch at all (the till's normal case) stays silent.
     */
    @Test
    void anUnknownNamedBatchAndANonPositiveLineLeaveATrace() {
        UUID receipt = Ids.next();

        int posted = sale.handle(
                new ApplySale(
                        receipt,
                        shop,
                        Instant.now(),
                        List.of(
                                new ApplySale.Line(null, 1, sku, Ids.next(), BigDecimal.ONE),
                                new ApplySale.Line(null, 2, sku, null, BigDecimal.ZERO),
                                new ApplySale.Line(null, 3, sku, null, BigDecimal.ONE))),
                at(shop));

        assertThat(posted).isEqualTo(2);
        assertThat(queries.balances(shop, sku, true, own(MPCS)))
                .extracting(LotBalance::qtyOnHand)
                .singleElement()
                .satisfies(q -> assertThat(q).isEqualByComparingTo("3"));
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SALE_BATCH_SUBSTITUTED"))
                .singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.after())).contains("[1]"));
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SALE_LINE_SKIPPED"))
                .singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.after())).contains("[2]"));
        assertThat(kernel.committedEvents()).isNotEmpty();
    }

    /** Wave 2, M5-01: a sale is a fact, so an expired lot sells; past its date on the receipt's day it is flagged. */
    @Test
    void aSaleFromABatchPastItsExpiryOnTheReceiptsDateIsPostedAndFlagged() {
        UUID old = fixture.batch(sku, MPCS, "OLD", LocalDate.of(2026, 1, 31));
        outer.run(
                own(MPCS),
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        shop,
                                        old,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal("4"),
                                        new BigDecimal("250"),
                                        null))),
                        own(MPCS)));
        kernel.reset();

        // Sold on its last day: no flag. Sold on 1 February: flagged, and posted all the same.
        sale.handle(
                new ApplySale(
                        Ids.next(),
                        shop,
                        Instant.parse("2026-01-31T10:00:00Z"),
                        List.of(new ApplySale.Line(null, 1, sku, old, BigDecimal.ONE))),
                at(shop));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .doesNotContain("SALE_OF_EXPIRED");

        kernel.reset();
        UUID receipt = Ids.next();
        int posted = sale.handle(
                new ApplySale(
                        receipt,
                        shop,
                        Instant.parse("2026-02-01T03:00:00Z"),
                        List.of(new ApplySale.Line(null, 1, sku, old, BigDecimal.ONE))),
                at(shop));

        assertThat(posted).isEqualTo(1);
        assertThat(queries.movementsOf(receipt, own(MPCS))).singleElement().satisfies(m -> assertThat(m.batchId())
                .isEqualTo(old));
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SALE_OF_EXPIRED"))
                .singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.after())).contains("[1]", "2026-02-01"));
    }

    /** Wave 2, M5-05: an item the shop never held is sold from its newest batch, the lot going below zero. */
    @Test
    void aSaleOfAnItemTheShopNeverHeldPostsAgainstItsNewestBatchAndGoesNegative() {
        UUID item = fixture.sku(MPCS, "NEVERHELD");
        fixture.batch(item, MPCS, "N-OLD", LocalDate.of(2027, 1, 31));
        UUID newest = fixture.batch(item, MPCS, "N-NEW", LocalDate.of(2027, 6, 30));
        UUID receipt = Ids.next();

        int posted = sale.handle(
                new ApplySale(
                        receipt,
                        shop,
                        Instant.now(),
                        List.of(new ApplySale.Line(null, 1, item, null, new BigDecimal("2")))),
                at(shop));

        assertThat(posted).isEqualTo(1);
        assertThat(queries.balances(shop, item, true, own(MPCS)))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.batchId()).isEqualTo(newest);
                    assertThat(l.qtyOnHand()).isEqualByComparingTo("-2");
                    assertThat(l.negative()).isTrue();
                });
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_POSTED", "STOCK_LOT_NEGATIVE", "STOCK_SOLD", "SALE_WITHOUT_LOT")
                .doesNotContain("SALE_LINE_UNRESOLVED");
    }

    /** Wave 2, M5-04: a till's stock fact M5 has no hook for yet is flagged, never dropped; central's own event is not. */
    @Test
    void aTillsStockFactWithNoHookYetIsFlaggedAndCentralsOwnEventIsNot() {
        UUID count = Ids.next();
        JsonNode bundle = mapper.valueToTree(Map.of("document", Map.of("document_id", count.toString())));

        tillFacts.onCountRecorded(bundle, at(shop));

        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("TILL_FACT_NOT_APPLIED");
            assertThat(String.valueOf(a.after())).contains("count.recorded.v1", count.toString());
        });
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(TillFactNotApplied.class, e -> {
            assertThat(e.factType()).isEqualTo("count.recorded.v1");
            assertThat(e.documentId()).isEqualTo(count);
            assertThat(e.locationId()).isEqualTo(shop);
        });

        // transfer.issued.v1 is also central's own event: delivered with no device, it is not a till fact.
        kernel.reset();
        tillFacts.onTransferIssued(
                mapper.valueToTree(Map.of("transferId", Ids.next().toString())), own(MPCS));
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theGuardWantsTheDevicesShop() {
        assertThatThrownBy(() -> sale.handle(new ApplySale(Ids.next(), shop, Instant.now(), List.of()), own(MPCS)))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.sale.location_required"));
        assertThatThrownBy(() -> sale.handle(new ApplySale(null, shop, Instant.now(), List.of()), at(shop)))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.ledger.document_required"));
        assertThat(kernel.committedAudit()).isEmpty();
    }

    private static ScopeContext at(UUID location) {
        Scope scope = new Scope(MPCS, location);
        return new ScopeContext(
                null, Ids.next(), MPCS, List.of(scope), scope, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }
}
