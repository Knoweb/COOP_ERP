package lk.coopfed.knoweb.m2catalogue.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BatchCorrected;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.CorrectBatch;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisterSupplier;
import lk.coopfed.knoweb.m2catalogue.api.RegisteredBatch;
import lk.coopfed.knoweb.m2catalogue.api.SupplierRegistered;
import lk.coopfed.knoweb.m2catalogue.internal.supplier.RegisterSupplierHandler;
import lk.coopfed.knoweb.m2catalogue.query.BatchFilter;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RegisterBatch, CorrectBatch and RegisterSupplier (22A section 6): every guard with its failing
 * case, existing-or-create, the synthetic batch, the correction chain (the old batch SUPERSEDED,
 * the identity re-pointed, a correction by an entity that did not register the batch), what each
 * audits and publishes, and the batch queries of 22A section 7.
 */
class BatchHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID MPCS_A = UUID.fromString("0190e670-0000-7000-8000-000000000002");
    private static final UUID MPCS_B = UUID.fromString("0190e670-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e670-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e670-0000-7000-8000-000000000100");

    @Autowired
    BatchRegistration registration;

    @Autowired
    RegisterBatchHandler register;

    @Autowired
    CorrectBatchHandler correct;

    @Autowired
    RegisterSupplierHandler suppliers;

    @Autowired
    BatchQueries queries;

    @Autowired
    ObjectMapper mapper;

    // M5 owns the lots; a stub answers for it (no lots unless a test says otherwise).
    @MockitoBean
    InventoryLotQuery lots;

    /** Batch-tracked, expiry-tracked, printed MRP: the Federation's SHARED milk powder. */
    private final UUID trackedSku = Ids.next();
    /** Not batch-tracked, no MRP: A's LOCAL loose rice. */
    private final UUID looseSku = Ids.next();

    private final UUID draftSku = Ids.next();
    private final UUID inactiveSku = Ids.next();
    private final UUID localSkuOfB = Ids.next();

    private UUID supplierOfF;

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'EA', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2BATCH', 'M2 batch tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        insertSku(admin, trackedSku, "BT-TRACKED", FEDERATION, "SHARED", true, true, true);
        insertSku(admin, looseSku, "BT-LOOSE", MPCS_A, "LOCAL", false, false, false);
        insertSku(admin, draftSku, "BT-DRAFT", MPCS_A, "DRAFT", false, false, false);
        insertSku(admin, inactiveSku, "BT-INACTIVE", MPCS_A, "INACTIVE", false, false, false);
        insertSku(admin, localSkuOfB, "BT-LOCAL-B", MPCS_B, "LOCAL", false, false, false);

        supplierOfF = suppliers.handle(new RegisterSupplier("Lanka Milk Foods"), own(FEDERATION));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.batch, catalogue.batch_key, catalogue.supplier, catalogue.sku cascade");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    // ---- RegisterBatch ----------------------------------------------------------------------

    @Test
    void aNewBatchIsRegisteredAuditedAndPublishedAndTheSameIdentityAnswersIt() {
        RegisteredBatch first = registration.register(milk("B2411A", "1080.00"), own(FEDERATION));

        assertThat(first.created()).isTrue();
        assertThat(first.synthetic()).isFalse();
        assertThat(first.batchNo()).isEqualTo("B2411A");
        BatchView row = queries.getBatch(first.batchId(), own(MPCS_B)).orElseThrow();
        assertThat(row.status()).isEqualTo("REGISTERED");
        assertThat(row.ownerEntityId()).isEqualTo(FEDERATION);
        assertThat(row.supplierId()).isEqualTo(supplierOfF);
        assertThat(row.printedMrp()).isEqualByComparingTo("1080.00");

        assertThat(audit("BATCH_REGISTERED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("batch");
            assertThat(record.subject().id()).isEqualTo(first.batchId());
            assertThat(record.before()).isNull();
        });
        assertThat(events(BatchRegistered.class)).singleElement().satisfies(event -> {
            assertThat(event.batchId()).isEqualTo(first.batchId());
            assertThat(event.skuId()).isEqualTo(trackedSku);
            assertThat(event.supplierId()).isEqualTo(supplierOfF);
            assertThat(event.ownerEntityId()).isEqualTo(FEDERATION);
            assertThat(event.synthetic()).isFalse();
        });

        // The distributor's GRN of the same physical batch cites it (doc 22 section 3.7): the same
        // batch returned, nothing written, audited or published.
        kernel.reset();
        RegisteredBatch again = registration.register(milk(" B2411A ", "1080.00"), own(MPCS_B));
        assertThat(again).isEqualTo(new RegisteredBatch(first.batchId(), "B2411A", false, false));
        assertThat(count("catalogue.batch")).isEqualTo(1);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // Another supplier's B2411A is another batch.
        UUID otherSupplier = suppliers.handle(new RegisterSupplier("Other Dairy"), own(FEDERATION));
        RegisteredBatch other = register.handle(
                new RegisterBatch(
                        trackedSku,
                        otherSupplier,
                        "B2411A",
                        null,
                        LocalDate.of(2027, 3, 31),
                        new BigDecimal("1080.00"),
                        null,
                        null,
                        null),
                own(FEDERATION));
        assertThat(other.created()).isTrue();
        assertThat(other.batchId()).isNotEqualTo(first.batchId());
    }

    @Test
    void anItemThatIsNotBatchTrackedGetsASyntheticBatchPerDocumentLine() {
        RegisteredBatch line1 = register.handle(loose(null, "GRN-000017", 1), own(MPCS_A));
        RegisteredBatch line1Again = register.handle(loose("IGNORED", "GRN-000017", 1), own(MPCS_A));
        RegisteredBatch line2 = register.handle(loose(null, "GRN-000017", 2), own(MPCS_A));

        assertThat(line1.synthetic()).isTrue();
        assertThat(line1.batchNo()).isEqualTo("S-GRN-000017-1");
        assertThat(line1Again.batchId()).as("the same line is the same batch").isEqualTo(line1.batchId());
        assertThat(line1Again.created()).isFalse();
        assertThat(line2.batchNo()).isEqualTo("S-GRN-000017-2");
        assertThat(queries.getBatch(line1.batchId(), own(MPCS_A)).orElseThrow().synthetic())
                .isTrue();

        // A batch-tracked item whose supplier printed no number gets one too.
        RegisteredBatch unnumbered = register.handle(
                new RegisterBatch(
                        trackedSku,
                        supplierOfF,
                        " ",
                        null,
                        LocalDate.of(2027, 1, 31),
                        new BigDecimal("990.00"),
                        null,
                        "GRN-9",
                        3),
                own(FEDERATION));
        assertThat(unnumbered.batchNo()).isEqualTo("S-GRN-9-3");
        assertThat(unnumbered.synthetic()).isTrue();

        // Without the document line there is nothing to name it by.
        refused(() -> register.handle(loose(null, null, 1), own(MPCS_A)), "request.field.required");
        refused(() -> register.handle(loose(null, "GRN-1", null), own(MPCS_A)), "request.field.required");
        refused(() -> register.handle(loose(null, "GRN-1", 0), own(MPCS_A)), "request.field.required");
        refused(() -> register.handle(loose(null, "G".repeat(40), 1), own(MPCS_A)), "m2.batch.batch_no_too_long");
    }

    @Test
    void everyGuardOfRegisterBatchRefusesItsCase() {
        refused(() -> register.handle(null, own(MPCS_A)), "request.invalid");
        refused(() -> register.handle(loose(null, "D", 1), viewOnly(MPCS_A)), "scope.invalid");
        refused(
                () -> register.handle(new RegisterBatch(null, null, "X", null, null, null, null, "D", 1), own(MPCS_A)),
                "request.field.required");
        // Another entity's LOCAL item is not found; a DRAFT or INACTIVE item is not active.
        refused(() -> register.handle(looseOf(localSkuOfB), own(MPCS_A)), "m2.sku.not_found");
        refused(() -> register.handle(looseOf(draftSku), own(MPCS_A)), "m2.batch.sku_not_active");
        refused(() -> register.handle(looseOf(inactiveSku), own(MPCS_A)), "m2.batch.sku_not_active");
        // The supplier must exist and be ACTIVE.
        refused(
                () -> register.handle(
                        new RegisterBatch(looseSku, Ids.next(), null, null, null, null, null, "D", 1), own(MPCS_A)),
                "m2.supplier.not_found");
        superuserJdbc().update("update catalogue.supplier set status = 'INACTIVE' where supplier_id = ?", supplierOfF);
        refused(() -> register.handle(milk("B1", "10.00"), own(FEDERATION)), "m2.supplier.not_active");
        superuserJdbc().update("update catalogue.supplier set status = 'ACTIVE' where supplier_id = ?", supplierOfF);
        // A batch number longer than the column.
        refused(() -> register.handle(milk("B".repeat(41), "10.00"), own(FEDERATION)), "m2.batch.batch_no_too_long");
        // MRP present when has_printed_mrp, and positive.
        refused(() -> register.handle(milk("B1", null), own(FEDERATION)), "m2.batch.mrp_required");
        refused(() -> register.handle(milk("B1", "0"), own(FEDERATION)), "m2.batch.mrp_invalid");
        // Expiry when expiry_tracked, and not before the manufacture date.
        refused(
                () -> register.handle(
                        new RegisterBatch(
                                trackedSku, supplierOfF, "B1", null, null, new BigDecimal("10"), null, null, null),
                        own(FEDERATION)),
                "m2.batch.expiry_required");
        refused(
                () -> register.handle(
                        new RegisterBatch(
                                trackedSku,
                                supplierOfF,
                                "B1",
                                LocalDate.of(2027, 1, 2),
                                LocalDate.of(2027, 1, 1),
                                new BigDecimal("10"),
                                null,
                                null,
                                null),
                        own(FEDERATION)),
                "m2.batch.dates_invalid");

        assertThat(count("catalogue.batch")).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void twoRegistrationsOfTheSameBatchTogetherBothAnswerOneBatch() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<RegisteredBatch> call = () -> registration.register(milk("RACE-1", "500.00"), own(FEDERATION));
            Future<RegisteredBatch> one = pool.submit(call);
            Future<RegisteredBatch> two = pool.submit(call);
            assertThat(one.get().batchId()).isEqualTo(two.get().batchId());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("catalogue.batch")).isEqualTo(1);
    }

    // ---- CorrectBatch -----------------------------------------------------------------------

    @Test
    void theFederationCorrectsTheMrpByAReplacementThatTakesOverTheIdentity() {
        RegisteredBatch keyed = registration.register(milk("B2411A", "980.00"), own(MPCS_B));
        kernel.reset();

        UUID replacement = correct.handle(
                new CorrectBatch(keyed.batchId(), new BigDecimal("1080.00"), null, "MISKEYED", "980 for 1,080"),
                own(FEDERATION));

        BatchView old = queries.getBatch(keyed.batchId(), own(MPCS_A)).orElseThrow();
        BatchView now = queries.getBatch(replacement, own(MPCS_A)).orElseThrow();
        assertThat(old.status()).isEqualTo("SUPERSEDED");
        assertThat(now.status()).isEqualTo("REGISTERED");
        assertThat(now.correctsBatchId()).isEqualTo(keyed.batchId());
        assertThat(now.printedMrp()).isEqualByComparingTo("1080.00");
        assertThat(now.expiryDate()).isEqualTo(old.expiryDate());
        assertThat(now.batchNo()).isEqualTo("B2411A");
        assertThat(now.ownerEntityId()).as("the corrector's row").isEqualTo(FEDERATION);

        // The identity moved: the next GRN of B2411A cites the replacement.
        assertThat(registration.register(milk("B2411A", "1080.00"), own(MPCS_A)).batchId())
                .isEqualTo(replacement);

        assertThat(audit("BATCH_CORRECTED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(replacement);
            assertThat(((Map<?, ?>) record.before()).get("printedMrp")).isEqualTo(new BigDecimal("980.00"));
            assertThat(((Map<?, ?>) record.after()).get("correctsBatchId")).isEqualTo(keyed.batchId());
            assertThat(record.reason()).isEqualTo("MISKEYED: 980 for 1,080");
        });
        assertThat(events(BatchCorrected.class)).singleElement().satisfies(event -> {
            assertThat(event.batchId()).isEqualTo(replacement);
            assertThat(event.correctsBatchId()).isEqualTo(keyed.batchId());
            assertThat(event.printedMrp()).isEqualByComparingTo("1080.00");
            assertThat(event.reasonCode()).isEqualTo("MISKEYED");
        });

        // The chain: the list shows both, newest first.
        assertThat(queries.listBatches(new BatchFilter(trackedSku, "B2411A", null, null), own(MPCS_A)))
                .extracting(BatchView::batchId)
                .containsExactly(replacement, keyed.batchId());
    }

    @Test
    void aLotHolderThatDidNotRegisterTheBatchCorrectsItsExpiry() {
        RegisteredBatch keyed = registration.register(milk("B7", "500.00"), own(FEDERATION));
        doReturn(true).when(lots).holdsLotOf(keyed.batchId(), MPCS_A);
        kernel.reset();

        UUID replacement = correct.handle(
                new CorrectBatch(keyed.batchId(), null, LocalDate.of(2027, 6, 30), "EXPIRY_MISKEYED", null),
                own(MPCS_A));

        assertThat(queries.getBatch(keyed.batchId(), own(MPCS_A)).orElseThrow().status())
                .isEqualTo("SUPERSEDED");
        BatchView now = queries.getBatch(replacement, own(MPCS_A)).orElseThrow();
        assertThat(now.expiryDate()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(now.printedMrp()).isEqualByComparingTo("500.00");
        assertThat(now.ownerEntityId()).isEqualTo(MPCS_A);

        // What M5's consumer does with batch.corrected.v1 (doc 22 section 6.8: "M5 re-points
        // lots"): a stub holding lots by batch reads the event as published and moves them.
        BatchCorrected published = events(BatchCorrected.class).get(0);
        Map<UUID, Integer> lotsByBatch = new HashMap<>(Map.of(keyed.batchId(), 24));
        m5StubRepoints(roundTrip(published), lotsByBatch);
        assertThat(lotsByBatch).containsExactly(Map.entry(replacement, 24));
    }

    @Test
    void everyGuardOfCorrectBatchRefusesItsCase() {
        RegisteredBatch keyed = registration.register(milk("B8", "500.00"), own(FEDERATION));
        kernel.reset();

        refused(() -> correct.handle(null, own(FEDERATION)), "request.invalid");
        refused(() -> correct.handle(fix(keyed.batchId(), "600.00"), viewOnly(MPCS_A)), "scope.invalid");
        refused(() -> correct.handle(fix(null, "600.00"), own(FEDERATION)), "request.field.required");
        refused(() -> correct.handle(fix(Ids.next(), "600.00"), own(FEDERATION)), "m2.batch.not_found");
        // Neither the Federation nor a lot holder (the registering entity of another batch, even).
        refused(() -> correct.handle(fix(keyed.batchId(), "600.00"), own(MPCS_A)), "m2.batch.not_holder");
        refused(
                () -> correct.handle(
                        new CorrectBatch(keyed.batchId(), new BigDecimal("600"), null, " ", null), own(FEDERATION)),
                "m2.batch.reason_required");
        refused(() -> correct.handle(fix(keyed.batchId(), "-1"), own(FEDERATION)), "m2.batch.mrp_invalid");
        refused(() -> correct.handle(fix(keyed.batchId(), "500.0"), own(FEDERATION)), "m2.batch.correction_unchanged");
        refused(
                () -> correct.handle(new CorrectBatch(keyed.batchId(), null, null, "X", null), own(FEDERATION)),
                "m2.batch.correction_unchanged");
        assertThat(kernel.committedAudit()).isEmpty();

        // A corrected batch is not corrected again: its replacement is.
        UUID replacement = correct.handle(fix(keyed.batchId(), "600.00"), own(FEDERATION));
        refused(() -> correct.handle(fix(keyed.batchId(), "700.00"), own(FEDERATION)), "m2.batch.superseded");
        UUID second = correct.handle(fix(replacement, "700.00"), own(FEDERATION));
        assertThat(queries.getBatch(second, own(FEDERATION)).orElseThrow().correctsBatchId())
                .isEqualTo(replacement);
    }

    @Test
    void anExpiryBeforeTheManufactureDateIsRefused() {
        RegisteredBatch keyed = registration.register(
                new RegisterBatch(
                        trackedSku,
                        supplierOfF,
                        "B9",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 9, 1),
                        new BigDecimal("100"),
                        null,
                        null,
                        null),
                own(FEDERATION));
        refused(
                () -> correct.handle(
                        new CorrectBatch(keyed.batchId(), null, LocalDate.of(2026, 8, 1), "X", null), own(FEDERATION)),
                "m2.batch.dates_invalid");
    }

    // ---- RegisterSupplier and the lists -----------------------------------------------------

    @Test
    void aSupplierIsTheCallersOwnAuditedPublishedAndUniqueByName() {
        UUID supplier = suppliers.handle(new RegisterSupplier("  Ceylon Rice Mills "), own(MPCS_A));

        assertThat(queries.listSuppliers(own(MPCS_A))).singleElement().satisfies(view -> {
            assertThat(view.supplierId()).isEqualTo(supplier);
            assertThat(view.name()).isEqualTo("Ceylon Rice Mills");
            assertThat(view.status()).isEqualTo("ACTIVE");
            assertThat(view.ownerEntityId()).isEqualTo(MPCS_A);
        });
        assertThat(audit("SUPPLIER_REGISTERED"))
                .singleElement()
                .satisfies(record -> assertThat(record.subject().id()).isEqualTo(supplier));
        assertThat(events(SupplierRegistered.class)).containsExactly(new SupplierRegistered(supplier, MPCS_A));

        refused(
                () -> suppliers.handle(new RegisterSupplier("Ceylon Rice Mills"), own(MPCS_A)),
                "m2.supplier.name_taken");
        // Another entity may use the same name for its own supplier.
        suppliers.handle(new RegisterSupplier("Ceylon Rice Mills"), own(MPCS_B));
        refused(() -> suppliers.handle(new RegisterSupplier(" "), own(MPCS_A)), "request.field.required");
        refused(() -> suppliers.handle(null, own(MPCS_A)), "request.invalid");
        refused(
                () -> suppliers.handle(new RegisterSupplier("At a shop"), ScopeContext.dev(USER, MPCS_A, Ids.next())),
                "scope.invalid");
    }

    @Test
    void theBatchListFiltersByNumberAndExpiry() {
        registration.register(milk("E1", "10"), own(FEDERATION));
        register.handle(
                new RegisterBatch(
                        trackedSku,
                        supplierOfF,
                        "E2",
                        null,
                        LocalDate.of(2026, 1, 1),
                        new BigDecimal("10"),
                        null,
                        null,
                        null),
                own(FEDERATION));

        assertThat(queries.listBatches(new BatchFilter(trackedSku, null, null, null), own(MPCS_A)))
                .hasSize(2);
        assertThat(queries.listBatches(new BatchFilter(trackedSku, null, LocalDate.of(2026, 6, 1), null), own(MPCS_A)))
                .extracting(BatchView::batchNo)
                .containsExactly("E2");
        assertThat(queries.listBatches(new BatchFilter(trackedSku, null, null, 1), own(MPCS_A)))
                .hasSize(1);
        refused(
                () -> queries.listBatches(new BatchFilter(null, null, null, null), own(MPCS_A)),
                "request.field.required");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** M5's lot re-pointing as a stub: every lot of the corrected batch now names the replacement. */
    private static void m5StubRepoints(BatchCorrected event, Map<UUID, Integer> lotsByBatch) {
        Integer quantity = lotsByBatch.remove(event.correctsBatchId());
        if (quantity != null) {
            lotsByBatch.merge(event.batchId(), quantity, Integer::sum);
        }
    }

    private BatchCorrected roundTrip(BatchCorrected event) {
        try {
            return mapper.readValue(mapper.writeValueAsString(event), BatchCorrected.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RegisterBatch milk(String batchNo, String mrp) {
        return new RegisterBatch(
                trackedSku,
                supplierOfF,
                batchNo,
                null,
                LocalDate.of(2027, 3, 31),
                mrp == null ? null : new BigDecimal(mrp),
                Ids.next(),
                null,
                null);
    }

    private RegisterBatch loose(String batchNo, String documentNo, Integer line) {
        return new RegisterBatch(looseSku, null, batchNo, null, null, null, Ids.next(), documentNo, line);
    }

    private RegisterBatch looseOf(UUID skuId) {
        return new RegisterBatch(skuId, null, null, null, null, null, null, "GRN-1", 1);
    }

    private static CorrectBatch fix(UUID batchId, String mrp) {
        return new CorrectBatch(batchId, new BigDecimal(mrp), null, "MISKEYED", null);
    }

    private static void insertSku(
            JdbcTemplate admin,
            UUID skuId,
            String code,
            UUID owner,
            String status,
            boolean batchTracked,
            boolean expiryTracked,
            boolean hasPrintedMrp) {
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id, batch_tracked, expiry_tracked, has_printed_mrp, prior_status)
                values (?, ?, ?, ?, ?, ?, ?, 'EA', ?, ?, ?, ?, ?)
                """,
                skuId,
                code,
                owner,
                status,
                code,
                code,
                code,
                TAX_CATEGORY,
                batchTracked,
                expiryTracked,
                hasPrintedMrp,
                "INACTIVE".equals(status) ? "LOCAL" : null);
    }

    private static int count(String table) {
        return superuserJdbc().queryForObject("select count(*) from " + table, Integer.class);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    /** A read-only class: no batch or supplier is written in it. */
    private static ScopeContext viewOnly(UUID entity) {
        Scope scope = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
