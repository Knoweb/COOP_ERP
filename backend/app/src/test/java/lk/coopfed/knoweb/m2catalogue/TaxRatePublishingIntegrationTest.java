package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.DefineTaxCategory;
import lk.coopfed.knoweb.m2catalogue.api.PublishTaxRate;
import lk.coopfed.knoweb.m2catalogue.api.TaxCategoryDefined;
import lk.coopfed.knoweb.m2catalogue.api.TaxRatePublished;
import lk.coopfed.knoweb.m2catalogue.internal.tax.DefineTaxCategoryHandler;
import lk.coopfed.knoweb.m2catalogue.internal.tax.PublishTaxRateHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.TaxRateHistoryView;
import lk.coopfed.knoweb.m2catalogue.query.TaxRateView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task CAT-05 integration test: DefineTaxCategory, PublishTaxRate with future apply_from,
 * and proof that the rate in force applies strictly from that date onwards.
 */
class TaxRatePublishingIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID MPCS = UUID.fromString("0190e640-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e640-0000-7000-8000-000000000010");

    private static final String TEST_CAT_CODE = "TEST_VAT";

    @Autowired
    private DefineTaxCategoryHandler defineCategory;

    @Autowired
    private PublishTaxRateHandler publishRate;

    @Autowired
    private CatalogueQueries queries;

    private UUID testCategoryId;
    private UUID testSkuId;
    private LocalDate today;

    @BeforeEach
    void arrange() {
        clean();
        today = LocalDate.now();

        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");

        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.update(
                "delete from catalogue.tax_rate where tax_category_id in (select tax_category_id from catalogue.tax_category where code = ?)",
                TEST_CAT_CODE);
        admin.update("delete from catalogue.tax_category where code = ?", TEST_CAT_CODE);
    }

    @Test
    void federationDefinesTaxCategoryAuditsAndPublishes() {
        UUID categoryId = defineCategory.handle(
                new DefineTaxCategory(TEST_CAT_CODE, "Test VAT", "පරීක්ෂණ වැට්", "சோதனை வாட்"), own(FEDERATION));

        assertThat(categoryId).isNotNull();

        List<Map<String, Object>> rows = superuserJdbc()
                .queryForList(
                        "select code, name_en, name_si, name_ta, owner_entity_id from catalogue.tax_category where tax_category_id = ?",
                        categoryId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("code")).isEqualTo(TEST_CAT_CODE);
            assertThat(row.get("name_en")).isEqualTo("Test VAT");
            assertThat(row.get("name_si")).isEqualTo("පරීක්ෂණ වැට්");
            assertThat(row.get("name_ta")).isEqualTo("சோதனை வாட்");
            assertThat(row.get("owner_entity_id")).isEqualTo(FEDERATION);
        });

        assertThat(audit("TAX_CATEGORY_DEFINED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("tax_category");
            assertThat(record.subject().id()).isEqualTo(categoryId);
            assertThat(record.before()).isNull();
            Map<?, ?> after = (Map<?, ?>) record.after();
            assertThat(after.get("code")).isEqualTo(TEST_CAT_CODE);
            assertThat(after.get("nameEn")).isEqualTo("Test VAT");
        });

        assertThat(events(TaxCategoryDefined.class))
                .containsExactly(new TaxCategoryDefined(categoryId, FEDERATION, TEST_CAT_CODE));
    }

    @Test
    void defineTaxCategoryGuardsFailingCases() {
        // Non-federation caller refused
        refused(
                () -> defineCategory.handle(new DefineTaxCategory(TEST_CAT_CODE, "Test VAT", null, null), own(MPCS)),
                "m2.sku.federation_only");

        // Duplicate code refused
        defineCategory.handle(new DefineTaxCategory(TEST_CAT_CODE, "Test VAT", null, null), own(FEDERATION));

        kernel.reset();

        refused(
                () -> defineCategory.handle(
                        new DefineTaxCategory(TEST_CAT_CODE, "Another VAT", null, null), own(FEDERATION)),
                "m2.tax.category_code_exists");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void publishTaxRateWithFutureApplyFromDateAppliesOnlyFromThatDateOnwards() {
        // 1. Define tax category
        testCategoryId = defineCategory.handle(
                new DefineTaxCategory(TEST_CAT_CODE, "Test VAT", "පරීක්ෂණ වැට්", "சோதனை வாට්"), own(FEDERATION));

        // Create a test SKU linked to this tax category
        testSkuId = Ids.next();
        insertSku(superuserJdbc(), testSkuId, "SKU-VAT-TEST", FEDERATION, "SHARED", "EA", false, testCategoryId);

        kernel.reset();

        // 2. Publish initial rate: 18.00% starting on today
        publishRate.handle(new PublishTaxRate(testCategoryId, new BigDecimal("18.00"), today), own(FEDERATION));

        // Assert audit and published domain event
        assertThat(audit("TAX_RATE_PUBLISHED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("tax_category");
            assertThat(record.subject().id()).isEqualTo(testCategoryId);
            Map<?, ?> after = (Map<?, ?>) record.after();
            assertThat(after.get("ratePercent")).isEqualTo(new BigDecimal("18.00"));
            assertThat(after.get("applyFrom")).isEqualTo(today);
        });

        assertThat(events(TaxRatePublished.class))
                .containsExactly(new TaxRatePublished(testCategoryId, new BigDecimal("18.00"), today));

        // 3. Verify rate in force on today is 18.00%
        Optional<TaxRateView> initialRate = queries.taxRateInForce(testSkuId, today, own(FEDERATION));
        assertThat(initialRate).isPresent();
        assertThat(initialRate.get().ratePercent()).isEqualByComparingTo("18.00");
        assertThat(initialRate.get().effectiveFrom()).isEqualTo(today);

        kernel.reset();

        // 4. Publish future-dated rate: 20.00% starting on today + 10 days
        LocalDate futureApplyFrom = today.plusDays(10);
        publishRate.handle(
                new PublishTaxRate(testCategoryId, new BigDecimal("20.00"), futureApplyFrom), own(FEDERATION));

        // Assert audit and published domain event for the future rate
        assertThat(audit("TAX_RATE_PUBLISHED")).singleElement().satisfies(record -> {
            Map<?, ?> after = (Map<?, ?>) record.after();
            assertThat(after.get("ratePercent")).isEqualTo(new BigDecimal("20.00"));
            assertThat(after.get("applyFrom")).isEqualTo(futureApplyFrom);
        });

        assertThat(events(TaxRatePublished.class))
                .containsExactly(new TaxRatePublished(testCategoryId, new BigDecimal("20.00"), futureApplyFrom));

        // 5. Verify database rows: previous rate closed at futureApplyFrom - 1, new rate open
        List<TaxRateHistoryView> rates = queries.taxRates(testCategoryId, null, own(FEDERATION));
        assertThat(rates).hasSize(2);

        TaxRateHistoryView futureRow = rates.get(0);
        assertThat(futureRow.ratePercent()).isEqualByComparingTo("20.00");
        assertThat(futureRow.effectiveFrom()).isEqualTo(futureApplyFrom);
        assertThat(futureRow.effectiveTo()).isNull();

        TaxRateHistoryView previousRow = rates.get(1);
        assertThat(previousRow.ratePercent()).isEqualByComparingTo("18.00");
        assertThat(previousRow.effectiveFrom()).isEqualTo(today);
        assertThat(previousRow.effectiveTo()).isEqualTo(futureApplyFrom.minusDays(1));

        // 6. "Done when" CRITERIA:
        // Before futureApplyFrom (today and today + 9 days): rate in force MUST be 18.00%
        Optional<TaxRateView> rateOnToday = queries.taxRateInForce(testSkuId, today, own(FEDERATION));
        assertThat(rateOnToday).isPresent();
        assertThat(rateOnToday.get().ratePercent()).isEqualByComparingTo("18.00");

        Optional<TaxRateView> rateDayBefore =
                queries.taxRateInForce(testSkuId, futureApplyFrom.minusDays(1), own(FEDERATION));
        assertThat(rateDayBefore).isPresent();
        assertThat(rateDayBefore.get().ratePercent()).isEqualByComparingTo("18.00");

        // On futureApplyFrom (today + 10 days) and later: rate in force MUST be 20.00%
        Optional<TaxRateView> rateOnApplyFrom = queries.taxRateInForce(testSkuId, futureApplyFrom, own(FEDERATION));
        assertThat(rateOnApplyFrom).isPresent();
        assertThat(rateOnApplyFrom.get().ratePercent()).isEqualByComparingTo("20.00");
        assertThat(rateOnApplyFrom.get().effectiveFrom()).isEqualTo(futureApplyFrom);

        Optional<TaxRateView> rateInFuture =
                queries.taxRateInForce(testSkuId, futureApplyFrom.plusDays(30), own(FEDERATION));
        assertThat(rateInFuture).isPresent();
        assertThat(rateInFuture.get().ratePercent()).isEqualByComparingTo("20.00");
    }

    @Test
    void publishTaxRateGuardsFailingCases() {
        testCategoryId =
                defineCategory.handle(new DefineTaxCategory(TEST_CAT_CODE, "Test VAT", null, null), own(FEDERATION));

        kernel.reset();

        // Caller not Federation
        refused(
                () -> publishRate.handle(new PublishTaxRate(testCategoryId, new BigDecimal("18.00"), today), own(MPCS)),
                "m2.sku.federation_only");

        // Category not found
        refused(
                () -> publishRate.handle(
                        new PublishTaxRate(UUID.randomUUID(), new BigDecimal("18.00"), today), own(FEDERATION)),
                "m2.tax.category_not_found");

        // Rate percent negative
        refused(
                () -> publishRate.handle(
                        new PublishTaxRate(testCategoryId, new BigDecimal("-1.00"), today), own(FEDERATION)),
                "m2.tax.rate_invalid");

        // Rate percent > 100
        refused(
                () -> publishRate.handle(
                        new PublishTaxRate(testCategoryId, new BigDecimal("100.01"), today), own(FEDERATION)),
                "m2.tax.rate_invalid");

        // applyFrom in past
        refused(
                () -> publishRate.handle(
                        new PublishTaxRate(testCategoryId, new BigDecimal("18.00"), today.minusDays(1)),
                        own(FEDERATION)),
                "m2.tax.apply_from_in_past");

        // Valid publish
        publishRate.handle(new PublishTaxRate(testCategoryId, new BigDecimal("18.00"), today), own(FEDERATION));

        // Rate conflict: publishing again on the same applyFrom date
        refused(
                () -> publishRate.handle(
                        new PublishTaxRate(testCategoryId, new BigDecimal("19.00"), today), own(FEDERATION)),
                "m2.tax.rate_conflict");

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from catalogue.tax_rate where tax_category_id = ?",
                                Integer.class,
                                testCategoryId))
                .isEqualTo(1);
    }

    private static void insertSku(
            JdbcTemplate admin,
            UUID skuId,
            String code,
            UUID owner,
            String status,
            String baseUom,
            boolean weight,
            UUID taxCategoryId) {
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
                code,
                code,
                baseUom,
                weight,
                taxCategoryId);
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
