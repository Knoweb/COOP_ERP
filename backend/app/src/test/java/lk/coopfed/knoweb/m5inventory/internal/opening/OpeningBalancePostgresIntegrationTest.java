package lk.coopfed.knoweb.m5inventory.internal.opening;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.system;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.CountersignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceChanged;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceLine;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalancePosted;
import lk.coopfed.knoweb.m5inventory.api.PrepareOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.SignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The opening balance (doc 25 section 4.7 and flow 6.9; 25A M5-10 at demo scope): prepared,
 * signed, countersigned by another person, then the OPB document issued from the entity's series
 * and the OPENING_BALANCE movements posted, creating the lots and the entity average. Every guard
 * with its failing case; what each step audits and publishes.
 */
class OpeningBalancePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e679-0000-7000-8000-000000000002");
    private static final UUID OFFICER = UUID.fromString("0190e679-0000-7000-8000-000000000011");
    private static final UUID FEDERATION_OFFICER = UUID.fromString("0190e679-0000-7000-8000-000000000012");

    @Autowired
    PrepareOpeningBalanceHandler prepare;

    @Autowired
    SignOpeningBalanceHandler sign;

    @Autowired
    CountersignOpeningBalanceHandler countersign;

    @Autowired
    InventoryQueries queries;

    private InventoryFixture fixture;
    private UUID shop;
    private UUID sku;
    private UUID batch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5OB", "MPCS");
        shop = fixture.location(MPCS, "SHOP");
        sku = fixture.sku(MPCS, "DHAL1");
        batch = fixture.batch(sku, MPCS, "D1", LocalDate.of(2027, 5, 31));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void preparedSignedAndCountersignedTheOpeningBalanceIsIssuedAndPosted() {
        UUID id = prepare.handle(command(line("24", "310.5"), damagedLine("2", "310.5")), own(MPCS));
        assertThat(queries.openingBalance(id, own(MPCS))).hasValueSatisfying(b -> {
            assertThat(b.status()).isEqualTo("DRAFT");
            assertThat(b.lines()).hasSize(2);
            assertThat(b.preparedBy()).isEqualTo(InventoryFixture.USER);
        });
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("OPB_PREPARED");
        assertThat(events(OpeningBalanceChanged.class)).singleElement().satisfies(e -> assertThat(e.status())
                .isEqualTo("DRAFT"));

        kernel.reset();
        sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER));
        assertThat(queries.openingBalance(id, own(MPCS)).orElseThrow().status()).isEqualTo("SIGNED_ENTITY");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("OPB_SIGNED_ENTITY");

        kernel.reset();
        UUID document = countersign.handle(new CountersignOpeningBalance(id), own(MPCS, FEDERATION_OFFICER));

        assertThat(queries.openingBalance(id, own(MPCS))).hasValueSatisfying(b -> {
            assertThat(b.status()).isEqualTo("POSTED");
            assertThat(b.signedEntityBy()).isEqualTo(OFFICER);
            assertThat(b.countersignedBy()).isEqualTo(FEDERATION_OFFICER);
            assertThat(b.documentId()).isEqualTo(document);
        });
        Map<String, Object> issued = superuserJdbc()
                .queryForMap(
                        "select doc_type_code, status, doc_number_display, owner_entity_id, location_id"
                                + " from kernel.document where document_id = ?",
                        document);
        assertThat(issued.get("doc_type_code")).isEqualTo("OPB");
        assertThat(issued.get("owner_entity_id")).isEqualTo(MPCS);
        assertThat((String) issued.get("doc_number_display")).startsWith("M5OB-OPB-");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document_line where document_id = ?",
                                Integer.class,
                                document))
                .isEqualTo(2);

        List<LotBalance> lots = queries.balances(shop, null, false, own(MPCS));
        assertThat(lots)
                .extracting(LotBalance::condition, l -> l.qtyOnHand().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GOOD", 24),
                        org.assertj.core.groups.Tuple.tuple("DAMAGED", 2));
        assertThat(queries.movementsOf(document, own(MPCS)))
                .allSatisfy(m -> assertThat(m.movementType()).isEqualTo("OPENING_BALANCE"))
                .hasSize(2);
        assertThat(queries.entityAverageCost(sku, own(MPCS)))
                .hasValueSatisfying(c -> assertThat(c.avgCost()).isEqualByComparingTo("310.5"));

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("DOCUMENT_ISSUED", "STOCK_POSTED", "OPB_POSTED");
        assertThat(events(OpeningBalancePosted.class)).singleElement().satisfies(e -> {
            assertThat(e.documentId()).isEqualTo(document);
            assertThat(e.lines()).isEqualTo(2);
            assertThat(e.locationId()).isEqualTo(shop);
        });

        // A second opening balance for the location is refused: stock has moved there (flow 6.9).
        assertProblem(() -> prepare.handle(command(line("1", "1")), own(MPCS)), "m5.opening.location_has_stock");
    }

    // ---- guards ------------------------------------------------------------------------------

    @Test
    void prepareGuards() {
        UUID elsewhere = fixture.location(UUID.fromString("0190e679-0000-7000-8000-000000000003"), "SHOP");
        assertProblem(() -> prepare.handle(command(line("1", "1")), viewOnly(MPCS)), "m5.scope.own_required");
        assertProblem(
                () -> prepare.handle(new PrepareOpeningBalance(elsewhere, List.of(line("1", "1"))), own(MPCS)),
                "m5.location.not_in_scope");
        assertProblem(
                () -> prepare.handle(new PrepareOpeningBalance(shop, List.of()), own(MPCS)),
                "m5.opening.lines_required");
        assertProblem(() -> prepare.handle(command(line("0", "1")), own(MPCS)), "m5.opening.line_invalid");
        assertProblem(() -> prepare.handle(command(line("1", "-1")), own(MPCS)), "m5.opening.line_invalid");
        assertProblem(
                () -> prepare.handle(
                        new PrepareOpeningBalance(
                                shop,
                                List.of(new OpeningBalanceLine(
                                        Ids.next(), LotCondition.GOOD, BigDecimal.ONE, BigDecimal.ONE))),
                        own(MPCS)),
                "m5.batch.not_found");
        assertThat(kernel.committedAudit()).isEmpty();

        prepare.handle(command(line("1", "1")), own(MPCS));
        assertProblem(() -> prepare.handle(command(line("1", "1")), own(MPCS)), "m5.opening.already_open");
    }

    @Test
    void signatureGuards() {
        UUID id = prepare.handle(command(line("3", "10")), own(MPCS));
        kernel.reset();

        assertProblem(() -> sign.handle(new SignOpeningBalance(id), system(MPCS)), "m5.opening.user_required");
        assertProblem(
                () -> sign.handle(new SignOpeningBalance(Ids.next()), own(MPCS, OFFICER)), "m5.opening.not_found");
        assertProblem(
                () -> countersign.handle(new CountersignOpeningBalance(id), own(MPCS, FEDERATION_OFFICER)),
                "m5.opening.not_signed");

        sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER));
        assertProblem(() -> sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER)), "m5.opening.not_draft");
        kernel.reset();
        assertProblem(
                () -> countersign.handle(new CountersignOpeningBalance(id), own(MPCS, OFFICER)),
                "m5.opening.countersigner_is_signer");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(queries.balances(shop, null, true, own(MPCS))).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------

    private PrepareOpeningBalance command(OpeningBalanceLine... lines) {
        return new PrepareOpeningBalance(shop, List.of(lines));
    }

    private OpeningBalanceLine line(String qty, String cost) {
        return new OpeningBalanceLine(batch, LotCondition.GOOD, new BigDecimal(qty), new BigDecimal(cost));
    }

    private OpeningBalanceLine damagedLine(String qty, String cost) {
        return new OpeningBalanceLine(batch, LotCondition.DAMAGED, new BigDecimal(qty), new BigDecimal(cost));
    }

    /** A read-only class: it prepares nothing. */
    private static lk.coopfed.knoweb.kernel.api.ScopeContext viewOnly(UUID entity) {
        lk.coopfed.knoweb.kernel.api.Scope scope = new lk.coopfed.knoweb.kernel.api.Scope(entity, null);
        return new lk.coopfed.knoweb.kernel.api.ScopeContext(
                InventoryFixture.USER,
                null,
                entity,
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
