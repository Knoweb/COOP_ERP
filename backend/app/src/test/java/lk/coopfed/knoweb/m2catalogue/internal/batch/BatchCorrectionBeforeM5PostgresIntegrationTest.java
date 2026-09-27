package lk.coopfed.knoweb.m2catalogue.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BatchCorrected;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.CorrectBatch;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisteredBatch;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Who corrects a batch until M5 exists (decided 27 September 2026 on the architect's delegation):
 * the Federation, and the entity that registered the batch or one it replaces, which M5 will name
 * as a lot holder the moment its GRN creates the lot (internal.integration.RegistrationLotQuery;
 * 22A section 6, CorrectBatch: "caller owns a lot of the batch or F"). No stub here: the real
 * InventoryLotQuery bean answers.
 */
class BatchCorrectionBeforeM5PostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID MPCS_A = UUID.fromString("0190e671-0000-7000-8000-000000000002");
    private static final UUID MPCS_B = UUID.fromString("0190e671-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e671-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e671-0000-7000-8000-000000000100");

    @Autowired
    BatchRegistration registration;

    @Autowired
    OuterCommand outer;

    @Autowired
    CorrectBatchHandler correct;

    @Autowired
    InventoryLotQuery lots;

    /** A's LOCAL item bought from a local supplier: batch-tracked, printed MRP. */
    private final UUID localSku = Ids.next();

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'EA', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2BEFOREM5', 'M2 before M5', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id, batch_tracked, expiry_tracked, has_printed_mrp)
                values (?, 'BM5-LOCAL', ?, 'LOCAL', 'Curd', 'Curd', 'Curd', 'EA', ?, true, true, true)
                """,
                localSku,
                MPCS_A,
                TAX_CATEGORY);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.batch, catalogue.batch_key, catalogue.supplier, catalogue.sku cascade");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void theRegisteringEntityCorrectsItsOwnLocalSupplyBatch() {
        RegisteredBatch keyed = registered(curd("C-0925", "90.00"), own(MPCS_A));
        kernel.reset();

        UUID replacement = correct.handle(fix(keyed.batchId(), "190.00"), own(MPCS_A));

        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("BATCH_CORRECTED");
            assertThat(record.subject().id()).isEqualTo(replacement);
        });
        assertThat(events(BatchCorrected.class)).singleElement().satisfies(event -> {
            assertThat(event.correctsBatchId()).isEqualTo(keyed.batchId());
            assertThat(event.printedMrp()).isEqualByComparingTo("190.00");
        });
    }

    @Test
    void anEntityThatNeitherRegisteredNorCorrectedTheBatchIsRefused() {
        RegisteredBatch keyed = registered(curd("C-0926", "90.00"), own(MPCS_A));
        kernel.reset();

        assertThatThrownBy(() -> correct.handle(fix(keyed.batchId(), "190.00"), own(MPCS_B)))
                .isInstanceOf(ProblemException.class)
                .satisfies(error ->
                        assertThat(((ProblemException) error).messageId()).isEqualTo("m2.batch.not_holder"));
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theRegisteringEntityStillCorrectsAfterTheFederationsCorrection() {
        RegisteredBatch keyed = registered(curd("C-0927", "90.00"), own(MPCS_A));
        UUID byFederation = correct.handle(fix(keyed.batchId(), "95.00"), own(FEDERATION));

        // The replacement is the Federation's row; A registered the batch it replaces.
        assertThat(inScope(MPCS_A, () -> lots.holdsLotOf(byFederation, MPCS_A))).isTrue();
        assertThat(inScope(MPCS_B, () -> lots.holdsLotOf(byFederation, MPCS_B))).isFalse();
        UUID byA = correct.handle(fix(byFederation, "190.00"), own(MPCS_A));
        assertThat(byA).isNotNull();
    }

    @Test
    void aSkuHasALotOnceABatchOfItIsRegistered() {
        // UpdateSku's question (22A section 6: base unit and tracking flags stay once lots exist).
        assertThat(inScope(MPCS_B, () -> lots.hasAnyLot(localSku))).isFalse();
        registered(curd("C-0928", "90.00"), own(MPCS_A));
        assertThat(inScope(MPCS_B, () -> lots.hasAnyLot(localSku)))
                .as("a batch is read by every scope")
                .isTrue();
    }

    private RegisteredBatch registered(RegisterBatch command, ScopeContext scope) {
        return outer.run(scope, () -> registration.register(command, scope));
    }

    /** The lot query as a guard asks it: inside a command, in the caller's scope. */
    private <T> T inScope(UUID entity, Supplier<T> query) {
        return outer.run(own(entity), query);
    }

    private RegisterBatch curd(String batchNo, String mrp) {
        return new RegisterBatch(
                localSku, null, batchNo, null, LocalDate.of(2026, 12, 31), new BigDecimal(mrp), Ids.next(), null, null);
    }

    private static CorrectBatch fix(UUID batchId, String mrp) {
        return new CorrectBatch(batchId, new BigDecimal(mrp), null, "MISKEYED", null);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
