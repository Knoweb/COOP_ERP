package lk.coopfed.knoweb.m2catalogue.internal.unit;

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
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ConversionDefined;
import lk.coopfed.knoweb.m2catalogue.api.DefineConversion;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every guard of DefineConversion (22A section 6) with its failing case, and what it audits and publishes. */
class DefineConversionHandlerPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190e640-0000-7000-8000-000000000001");
    private static final UUID MPCS = UUID.fromString("0190e640-0000-7000-8000-000000000002");
    private static final UUID OTHER_MPCS = UUID.fromString("0190e640-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e640-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e640-0000-7000-8000-000000000100");

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);

    @Autowired
    DefineConversionHandler define;

    private final UUID localSku = Ids.next();
    private final UUID weighedSku = Ids.next();
    private final UUID sharedSku = Ids.next();

    @BeforeEach
    void arrange() {
        JdbcTemplate admin = superuserJdbc();
        clean();

        for (String[] unit : new String[][] {{"EA", "false"}, {"KG", "true"}, {"G", "true"}, {"CASE", "false"}}) {
            admin.update(
                    "insert into catalogue.uom (uom_code, name_en, is_weight) values (?, ?, ?) on conflict do nothing",
                    unit[0],
                    unit[0],
                    Boolean.parseBoolean(unit[1]));
        }
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2CONV', 'M2 conversion tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);

        insertSku(admin, localSku, "CNV-LOCAL", MPCS, "LOCAL", "EA", false);
        insertSku(admin, weighedSku, "CNV-WEIGH", MPCS, "LOCAL", "KG", true);
        insertSku(admin, sharedSku, "CNV-SHARE", FEDERATION, "SHARED", "EA", false);

        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void theOwnerDefinesARowAuditsAndPublishes() {
        define.handle(new DefineConversion(localSku, "case", new BigDecimal("24"), JAN_1, null), own(MPCS));

        List<Map<String, Object>> rows = rows(localSku);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("uom_code")).isEqualTo("CASE");
            assertThat((BigDecimal) row.get("factor_to_base")).isEqualByComparingTo("24");
            assertThat(row.get("effective_from")).isEqualTo(java.sql.Date.valueOf(JAN_1));
            assertThat(row.get("effective_to")).isNull();
            assertThat(row.get("owner_entity_id")).isEqualTo(MPCS);
        });

        assertThat(audit("CONVERSION_DEFINED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("sku");
            assertThat(record.subject().id()).isEqualTo(localSku);
            assertThat(record.before()).isNull();
            assertThat(((Map<?, ?>) record.after()).get("uomCode")).isEqualTo("CASE");
        });
        assertThat(events(ConversionDefined.class))
                .containsExactly(new ConversionDefined(localSku, MPCS, "CASE", new BigDecimal("24"), JAN_1, null));
    }

    @Test
    void aNewRowOfTheSameUnitClosesTheOpenOneTheDayBefore() {
        define.handle(new DefineConversion(localSku, "CASE", new BigDecimal("24"), JAN_1, null), own(MPCS));
        kernel.reset();

        define.handle(new DefineConversion(localSku, "CASE", new BigDecimal("12"), JUL_1, null), own(MPCS));

        List<Map<String, Object>> rows = rows(localSku);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("effective_to")).isEqualTo(java.sql.Date.valueOf(JUL_1.minusDays(1)));
        assertThat((BigDecimal) rows.get(0).get("factor_to_base")).isEqualByComparingTo("24");
        assertThat(rows.get(1).get("effective_to")).isNull();
        assertThat((BigDecimal) rows.get(1).get("factor_to_base")).isEqualByComparingTo("12");

        assertThat(audit("CONVERSION_DEFINED")).singleElement().satisfies(record -> {
            assertThat(((Map<?, ?>) record.before()).get("effectiveTo")).isNull();
            Map<?, ?> after = (Map<?, ?>) record.after();
            assertThat(((Map<?, ?>) after.get("closed")).get("effectiveTo")).isEqualTo(JUL_1.minusDays(1));
            assertThat(((Map<?, ?>) after.get("defined")).get("factorToBase")).isEqualTo(new BigDecimal("12"));
        });
        assertThat(events(ConversionDefined.class))
                .extracting(ConversionDefined::factorToBase)
                .containsExactly(new BigDecimal("12"));
    }

    @Test
    void aRowThatOverlapsAnEarlierClosedOneIsTheExclusionProblem() {
        define.handle(new DefineConversion(localSku, "CASE", new BigDecimal("24"), JAN_1, JUL_1), own(MPCS));
        kernel.reset();

        // Starts inside the closed row: nothing to close (the row is not open), the constraint refuses.
        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "CASE", new BigDecimal("12"), JAN_1.plusMonths(3), null),
                        own(MPCS)),
                "m2.conversion.overlap");

        assertThat(rows(localSku)).hasSize(1);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aRowDatedBeforeTheOpenOneIsAnOverlapNotAClose() {
        define.handle(new DefineConversion(localSku, "CASE", new BigDecimal("24"), JUL_1, null), own(MPCS));
        kernel.reset();

        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "CASE", new BigDecimal("12"), JAN_1, null), own(MPCS)),
                "m2.conversion.overlap");

        assertThat(rows(localSku)).singleElement().satisfies(row -> assertThat(row.get("effective_to"))
                .isNull());
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void everyGuardRefusesBeforeAnythingIsWritten() {
        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "PALLET", new BigDecimal("24"), JAN_1, null), own(MPCS)),
                "m2.conversion.uom_unknown");
        refused(
                () -> define.handle(new DefineConversion(localSku, "CASE", BigDecimal.ZERO, JAN_1, null), own(MPCS)),
                "m2.conversion.factor_not_positive");
        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "CASE", new BigDecimal("-1"), JAN_1, null), own(MPCS)),
                "m2.conversion.factor_not_positive");
        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "CASE", new BigDecimal("24"), JUL_1, JAN_1), own(MPCS)),
                "m2.conversion.effective_range_invalid");
        refused(
                () -> define.handle(new DefineConversion(localSku, "EA", BigDecimal.ONE, JAN_1, null), own(MPCS)),
                "m2.conversion.base_unit");
        // A weighed item takes a weight unit, never a case (doc 22 section 3.2, ADR-07).
        refused(
                () -> define.handle(
                        new DefineConversion(weighedSku, "CASE", new BigDecimal("10"), JAN_1, null), own(MPCS)),
                "m2.conversion.weight_sku_count_unit");
        define.handle(new DefineConversion(weighedSku, "G", new BigDecimal("0.001"), JAN_1, null), own(MPCS));

        assertThat(rows(localSku)).isEmpty();
        assertThat(rows(weighedSku)).hasSize(1);
        assertThat(audit("CONVERSION_DEFINED")).hasSize(1);
    }

    @Test
    void onlyTheOwnerOfTheSkuDefinesItsConversions() {
        // A SHARED SKU is visible to a society, but its conversions are the Federation's.
        refused(
                () -> define.handle(
                        new DefineConversion(sharedSku, "CASE", new BigDecimal("24"), JAN_1, null), own(MPCS)),
                "m2.sku.owner_mismatch");
        // Another entity's LOCAL SKU is not even visible.
        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "CASE", new BigDecimal("24"), JAN_1, null), own(OTHER_MPCS)),
                "m2.sku.not_found");
        // A shop-scoped session cannot define catalogue data.
        refused(
                () -> define.handle(
                        new DefineConversion(localSku, "CASE", new BigDecimal("24"), JAN_1, null),
                        ScopeContext.dev(USER, MPCS, UUID.randomUUID())),
                "scope.invalid");

        assertThat(rows(sharedSku)).isEmpty();
        assertThat(rows(localSku)).isEmpty();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        define.handle(new DefineConversion(sharedSku, "CASE", new BigDecimal("24"), JAN_1, null), own(FEDERATION));
        assertThat(rows(sharedSku)).hasSize(1);
    }

    private static void insertSku(
            JdbcTemplate admin, UUID skuId, String code, UUID owner, String status, String baseUom, boolean weight) {
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, sold_by_weight,
                     tax_category_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                skuId,
                code,
                owner,
                status,
                code,
                code, // B-I1: a SHARED row needs its three names; the text does not matter here
                code,
                baseUom,
                weight,
                TAX_CATEGORY);
    }

    private static List<Map<String, Object>> rows(UUID skuId) {
        return superuserJdbc()
                .queryForList(
                        "select uom_code, factor_to_base, effective_from, effective_to, owner_entity_id"
                                + " from catalogue.sku_uom_conversion where sku_id = ? order by effective_from",
                        skuId);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
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
