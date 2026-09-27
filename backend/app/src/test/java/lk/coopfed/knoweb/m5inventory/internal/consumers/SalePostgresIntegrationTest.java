package lk.coopfed.knoweb.m5inventory.internal.consumers;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
                .contains("STOCK_POSTED", "STOCK_SOLD", "SALE_LINE_UNRESOLVED");

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
