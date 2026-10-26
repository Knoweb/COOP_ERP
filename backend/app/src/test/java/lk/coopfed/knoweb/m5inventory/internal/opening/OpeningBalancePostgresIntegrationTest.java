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
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m1party.api.ActivateLocation;
import lk.coopfed.knoweb.m1party.api.LocationActivated;
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
import lk.coopfed.knoweb.m5inventory.query.OpeningBalanceView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    Handles<ActivateLocation, UUID> activateLocation;

    @Autowired
    JdbcTemplate appJdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private InventoryFixture fixture;
    private UUID shop;
    private UUID sku;
    private UUID batch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(TEST_FEDERATION, "FED", "FEDERATION");
        fixture.entity(MPCS, "M5OB", "MPCS");
        shop = fixture.location(MPCS, "WAREHOUSE", "ONBOARDING");
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
        UUID document = countersign.handle(new CountersignOpeningBalance(id), federationOfficer());

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
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("OPB_POSTED"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.scope().userId()).isEqualTo(FEDERATION_OFFICER);
                    assertThat(a.scope().homeEntityId()).isEqualTo(TEST_FEDERATION);
                    assertThat(a.scope().entityId()).isEqualTo(MPCS);
                });
        assertThat(events(OpeningBalancePosted.class)).singleElement().satisfies(e -> {
            assertThat(e.ownerEntityId()).isEqualTo(MPCS);
            assertThat(e.documentId()).isEqualTo(document);
            assertThat(e.lines()).isEqualTo(2);
            assertThat(e.locationId()).isEqualTo(shop);
        });

        // A second opening balance for the location is refused: stock has moved there (flow 6.9).
        assertProblem(() -> prepare.handle(command(line("1", "1")), own(MPCS)), "m5.opening.location_has_stock");
    }

    @Test
    void aLineWithItsBatchAsCountedIsRetainedThenRegisteredAtFederationCountersign() {
        OpeningBalanceLine counted = new OpeningBalanceLine(
                null,
                LotCondition.GOOD,
                new BigDecimal("12"),
                new BigDecimal("95"),
                sku,
                "NEW-7",
                LocalDate.of(2027, 8, 31),
                new BigDecimal("120.00"));

        UUID id = prepare.handle(command(counted), own(MPCS));

        assertThat(queries.openingBalance(id, own(MPCS))).hasValueSatisfying(balance -> {
            OpeningBalanceView.Line retained = balance.lines().get(0);
            assertThat(retained.batchId()).isNull();
            assertThat(retained.batchNo()).isEqualTo("NEW-7");
            assertThat(retained.expiryDate()).isEqualTo(LocalDate.of(2027, 8, 31));
            assertThat(retained.printedMrp()).isEqualByComparingTo("120.00");
        });
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from catalogue.batch where sku_id = ? and batch_no = 'NEW-7'",
                                Integer.class,
                                sku))
                .isZero();

        sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER));
        kernel.reset();
        UUID document = countersign.handle(new CountersignOpeningBalance(id), federationOfficer());

        UUID registered = queries.openingBalance(id, own(MPCS))
                .orElseThrow()
                .lines()
                .get(0)
                .batchId();
        Map<String, Object> batchRow = superuserJdbc()
                .queryForMap(
                        "select sku_id, batch_no, expiry_date, owner_entity_id, origin_document_id"
                                + " from catalogue.batch where batch_id = ?",
                        registered);
        assertThat(batchRow.get("sku_id")).isEqualTo(sku);
        assertThat(batchRow.get("batch_no")).isEqualTo("NEW-7");
        assertThat(batchRow.get("owner_entity_id")).isEqualTo(MPCS);
        assertThat(batchRow.get("origin_document_id")).isEqualTo(id);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where document_id = ?",
                                Integer.class,
                                document))
                .isEqualTo(1);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("BATCH_REGISTERED", "DOCUMENT_ISSUED", "STOCK_POSTED", "OPB_POSTED");

        // M2's own guard answers at countersign: this item needs an expiry date. The whole
        // posting rolls back while the entity's signed balance and retained data remain.
        fixture.clean();
        fixture.entity(TEST_FEDERATION, "FED", "FEDERATION");
        fixture.entity(MPCS, "M5OB", "MPCS");
        shop = fixture.location(MPCS, "WAREHOUSE", "ONBOARDING");
        sku = fixture.sku(MPCS, "DHAL2");
        OpeningBalanceLine noExpiry = new OpeningBalanceLine(
                null, LotCondition.GOOD, BigDecimal.ONE, BigDecimal.ONE, sku, "NEW-8", null, new BigDecimal("120.00"));
        UUID invalid = prepare.handle(command(noExpiry), own(MPCS));
        sign.handle(new SignOpeningBalance(invalid), own(MPCS, OFFICER));
        kernel.reset();
        assertProblem(
                () -> countersign.handle(new CountersignOpeningBalance(invalid), federationOfficer()),
                "m2.batch.expiry_required");
        assertThat(queries.openingBalance(invalid, own(MPCS)).orElseThrow().status())
                .isEqualTo("SIGNED_ENTITY");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from catalogue.batch where sku_id = ? and batch_no = 'NEW-8'",
                                Integer.class,
                                sku))
                .isZero();
        assertThat(queries.balances(shop, null, true, own(MPCS))).isEmpty();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void v0015RestrictsFederationAccessAndMakesSignedBatchResolutionOneTimeAndNarrow() {
        OpeningBalanceLine unresolved = new OpeningBalanceLine(
                null,
                LotCondition.GOOD,
                new BigDecimal("12.000"),
                new BigDecimal("95.0000"),
                sku,
                "RLS-NEW-7",
                LocalDate.of(2027, 8, 31),
                new BigDecimal("120.00"));
        UUID openingBalanceId = prepare.handle(command(unresolved), own(MPCS));
        UUID lineId = superuserJdbc()
                .queryForObject(
                        "select line_id from inventory.opening_balance_line where opening_balance_id = ?",
                        UUID.class,
                        openingBalanceId);

        assertThat(visibleOpeningBalances(MPCS, null, openingBalanceId)).isOne();
        assertThat(visibleOpeningBalanceLines(MPCS, null, openingBalanceId)).isOne();

        UUID federationOffice = fixture.location(TEST_FEDERATION, "OFFICE");
        assertThat(visibleOpeningBalances(TEST_FEDERATION, federationOffice, openingBalanceId))
                .isZero();
        assertThat(visibleOpeningBalanceLines(TEST_FEDERATION, federationOffice, openingBalanceId))
                .isZero();
        UUID unrelatedEntity = UUID.fromString("0190e679-0000-7000-8000-000000000099");
        assertThat(visibleOpeningBalances(unrelatedEntity, null, openingBalanceId))
                .isZero();
        assertThat(visibleOpeningBalanceLines(unrelatedEntity, null, openingBalanceId))
                .isZero();

        assertThatThrownBy(() -> resolveBatch(MPCS, lineId, batch))
                .rootCause()
                .hasMessageContaining("opening balance batch can only be resolved after entity signature");

        sign.handle(new SignOpeningBalance(openingBalanceId), own(MPCS, OFFICER));
        assertThat(inDatabaseScope(
                        TEST_FEDERATION,
                        null,
                        () -> appJdbc.queryForObject(
                                "select opening_balance_id from inventory.opening_balance"
                                        + " where opening_balance_id = ? for update",
                                UUID.class,
                                openingBalanceId)))
                .isEqualTo(openingBalanceId);
        assertThat(inDatabaseScope(
                        TEST_FEDERATION,
                        null,
                        () -> appJdbc.queryForObject(
                                "select count(*) from inventory.opening_balance_line" + " where opening_balance_id = ?",
                                Integer.class,
                                openingBalanceId)))
                .isOne();
        assertThatThrownBy(() -> inDatabaseScope(
                        TEST_FEDERATION,
                        null,
                        () -> appJdbc.update(
                                "update inventory.opening_balance set status = 'POSTED'"
                                        + " where opening_balance_id = ?",
                                openingBalanceId)))
                .as("Federation scope may lock but never mutate the MPCS header")
                .rootCause()
                .hasMessageContaining("row-level security policy");
        assertThat(resolveBatch(TEST_FEDERATION, lineId, batch)).isZero();
        assertThat(resolveBatch(MPCS, lineId, batch)).isOne();

        UUID replacement = fixture.batch(sku, MPCS, "RLS-OTHER-8", LocalDate.of(2027, 9, 30));
        assertThatThrownBy(() -> resolveBatch(MPCS, lineId, replacement))
                .rootCause()
                .hasMessageContaining("opening balance batch can only be resolved once");

        for (String column : List.of(
                "opening_balance_id",
                "owner_entity_id",
                "location_id",
                "sku_id",
                "condition",
                "qty",
                "unit_cost",
                "batch_no",
                "expiry_date",
                "printed_mrp")) {
            assertThatThrownBy(() -> inDatabaseScope(
                            MPCS,
                            null,
                            () -> appJdbc.update(
                                    "update inventory.opening_balance_line set " + column + " = " + column
                                            + " where line_id = ?",
                                    lineId)))
                    .as("%s remains immutable", column)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
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
        // Neither a batch nor an item to register one for.
        assertProblem(
                () -> prepare.handle(
                        command(new OpeningBalanceLine(null, LotCondition.GOOD, BigDecimal.ONE, BigDecimal.ONE)),
                        own(MPCS)),
                "m5.opening.line_invalid");
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
    void onlyAnOnboardingLocationCanPrepareAndItCannotActivateUntilTheBalanceIsPosted() {
        superuserJdbc().update("update party.location set connectivity_spec_met = true where location_id = ?", shop);

        kernel.reset();
        assertProblem(
                () -> activateLocation.handle(new ActivateLocation(shop), own(MPCS)),
                "m5.opening.required_before_activation");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        UUID id = prepare.handle(command(line("3", "10")), own(MPCS));
        sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER));
        assertProblem(
                () -> activateLocation.handle(new ActivateLocation(shop), own(MPCS)),
                "m5.opening.required_before_activation");

        countersign.handle(new CountersignOpeningBalance(id), federationOfficer());
        kernel.reset();
        activateLocation.handle(new ActivateLocation(shop), own(MPCS));

        assertThat(superuserJdbc()
                        .queryForObject("select status from party.location where location_id = ?", String.class, shop))
                .isEqualTo("ACTIVE");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("LOCATION_ACTIVATED");
        assertThat(events(LocationActivated.class))
                .containsExactly(new LocationActivated(shop, MPCS, "WAREHOUSE", "ONBOARDING"));

        for (String status : List.of("PLANNED", "ACTIVE", "DORMANT")) {
            UUID outsideOnboarding = fixture.location(MPCS, "WAREHOUSE", status);
            assertProblem(
                    () -> prepare.handle(
                            new PrepareOpeningBalance(outsideOnboarding, List.of(line("1", "1"))), own(MPCS)),
                    "m5.opening.location_not_onboarding");
        }
    }

    @Test
    void signatureGuards() {
        UUID id = prepare.handle(command(line("3", "10")), own(MPCS));
        kernel.reset();

        assertProblem(() -> sign.handle(new SignOpeningBalance(id), system(MPCS)), "m5.opening.user_required");
        assertProblem(
                () -> sign.handle(new SignOpeningBalance(Ids.next()), own(MPCS, OFFICER)), "m5.opening.not_found");
        assertProblem(
                () -> countersign.handle(new CountersignOpeningBalance(id), federationOfficer()),
                "m5.opening.not_signed");

        sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER));
        assertProblem(() -> sign.handle(new SignOpeningBalance(id), own(MPCS, OFFICER)), "m5.opening.not_draft");
        kernel.reset();
        assertProblem(
                () -> countersign.handle(new CountersignOpeningBalance(id), own(MPCS, OFFICER)),
                "m5.opening.federation_required");
        assertProblem(
                () -> countersign.handle(new CountersignOpeningBalance(id), own(TEST_FEDERATION, OFFICER)),
                "m5.opening.countersigner_is_signer");
        UUID federationLocation = fixture.location(TEST_FEDERATION, "OFFICE");
        assertProblem(
                () -> countersign.handle(
                        new CountersignOpeningBalance(id), InventoryFixture.at(TEST_FEDERATION, federationLocation)),
                "m5.opening.federation_required");
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

    private static lk.coopfed.knoweb.kernel.api.ScopeContext federationOfficer() {
        return own(TEST_FEDERATION, FEDERATION_OFFICER);
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

    private int visibleOpeningBalances(UUID entityId, UUID locationId, UUID openingBalanceId) {
        return inDatabaseScope(
                entityId,
                locationId,
                () -> appJdbc.queryForObject(
                        "select count(*) from inventory.opening_balance where opening_balance_id = ?",
                        Integer.class,
                        openingBalanceId));
    }

    private int visibleOpeningBalanceLines(UUID entityId, UUID locationId, UUID openingBalanceId) {
        return inDatabaseScope(
                entityId,
                locationId,
                () -> appJdbc.queryForObject(
                        "select count(*) from inventory.opening_balance_line where opening_balance_id = ?",
                        Integer.class,
                        openingBalanceId));
    }

    private int resolveBatch(UUID entityId, UUID lineId, UUID batchId) {
        return inDatabaseScope(
                entityId,
                null,
                () -> appJdbc.update(
                        "update inventory.opening_balance_line set batch_id = ? where line_id = ?", batchId, lineId));
    }

    private <T> T inDatabaseScope(UUID entityId, UUID locationId, Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            appJdbc.queryForObject(
                    "select set_config('app.scope_entity_id', ?, true)", String.class, setting(entityId));
            appJdbc.queryForObject(
                    "select set_config('app.scope_location_id', ?, true)", String.class, setting(locationId));
            appJdbc.queryForObject("select set_config('app.scope_class', 'OWN', true)", String.class);
            appJdbc.queryForObject("select set_config('app.granted_entities', '{}', true)", String.class);
            return work.get();
        });
    }

    private static String setting(UUID value) {
        return value == null ? "" : value.toString();
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
