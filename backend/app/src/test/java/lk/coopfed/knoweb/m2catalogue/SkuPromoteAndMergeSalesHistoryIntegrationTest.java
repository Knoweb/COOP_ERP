package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.MergeIntoShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuMerged;
import lk.coopfed.knoweb.m2catalogue.internal.sku.ActivateLocalSkuHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.ActivateSharedSkuHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.CreateLocalSkuHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.MergeIntoSharedHandler;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuCommandRouter;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m5inventory.internal.consumers.SkuMergedConsumer;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves that when a local SKU merges into a shared one (CAT-03):
 * 1. M2 creates the sku_alias row and moves barcodes.
 * 2. M5 re-points existing stock lots to the new canonical SKU.
 * 3. M8 alias logic ensures historical sales facts and stock positions follow the new shared SKU.
 */
class SkuPromoteAndMergeSalesHistoryIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID MPCS = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e600-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e600-0000-7000-8000-000000000100");

    @DynamicPropertySource
    static void federation(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", FEDERATION::toString);
    }

    @Autowired
    CreateLocalSkuHandler createLocal;

    @Autowired
    SkuCommandRouter router;

    @Autowired
    ActivateLocalSkuHandler activateLocal;

    @Autowired
    ActivateSharedSkuHandler activateShared;

    @Autowired
    MergeIntoSharedHandler mergeHandler;

    @Autowired
    CatalogueQueries catalogueQueries;

    @Autowired
    SkuMergedConsumer m5SkuMergedConsumer;

    @Autowired
    ReportingQueries reportingQueries;

    @Autowired
    Clock clock;

    @Autowired
    ObjectMapper mapper;

    private ScopeContext mpcsScope;
    private ScopeContext fedScope;
    private LocalDate today;
    private UUID shopLocation;

    @BeforeEach
    void setup() {
        theDatabaseNamesTheFederation(FEDERATION);
        mpcsScope = scopeOf(MPCS);
        fedScope = scopeOf(FEDERATION);

        today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"));
        shopLocation = Ids.next();

        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.sku cascade");
        admin.execute("truncate table reporting.shop_sale_line_fact cascade");
        admin.execute("truncate table reporting.stock_position cascade");
        admin.execute("truncate table inventory.stock_lot cascade");

        admin.update(
                """
                insert into catalogue.uom (uom_code, name_en, is_weight)
                values ('EA', 'Each', false)
                on conflict do nothing
                """);
        admin.update(
                """
                insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)
                values (?, 'M2MRG', 'Standard', ?)
                on conflict (tax_category_id) do nothing
                """,
                TAX_CATEGORY,
                FEDERATION);
    }

    @AfterEach
    void cleanTax() {
        superuserJdbc().update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void whenLocalSkuMergesIntoSharedSkuItsStockAndSalesHistoryFollows() {
        // 1. Create and activate local SKU for MPCS
        UUID localSkuId = createLocal.handle(
                new CreateSku(details("Local Ceylon Tea", "දේශීය තේ", "உள்ளூர் தேநீர்", "EA")), mpcsScope);
        activateLocal.handle(new ActivateLocalSku(localSkuId), mpcsScope);

        // 2. Create and activate shared SKU for Federation
        UUID sharedSkuId = router.create(
                new CreateSku(details("Shared Ceylon Tea", "සිලෝන් තේ", "சிலோන් தேநீர்", "EA")), fedScope);
        activateShared.handle(new ActivateSharedSku(sharedSkuId), fedScope);

        String localSkuCode = superuserJdbc()
                .queryForObject("select sku_code from catalogue.sku where sku_id = ?", String.class, localSkuId);
        String sharedSkuCode = superuserJdbc()
                .queryForObject("select sku_code from catalogue.sku where sku_id = ?", String.class, sharedSkuId);

        // 3. Register a barcode on the local SKU
        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id)
                values ('4791000888001', 'EAN13', ?, 'EA', ?)
                """,
                        localSkuId,
                        MPCS);

        // 4. Seed stock lot in M5 for the local SKU
        UUID lotId = Ids.next();
        UUID batchId = Ids.next();
        superuserJdbc()
                .update(
                        """
                insert into inventory.stock_lot
                    (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, condition, qty_on_hand, unit_cost, received_at)
                values (?, ?, ?, ?, ?, 'GOOD', 25.000, 100.0000, ?)
                """,
                        lotId,
                        MPCS,
                        shopLocation,
                        batchId,
                        localSkuId,
                        Timestamp.from(clock.instant()));

        // 5. Seed stock position in M8 for the local SKU
        superuserJdbc()
                .update(
                        """
                insert into reporting.stock_position
                    (location_id, batch_id, condition, owner_entity_id, canonical_sku_id, qty_on_hand, unit_cost, last_source, last_movement_seq, freshness)
                values (?, ?, 'GOOD', ?, ?, 25.000, 100.0000, 'central', 1, ?)
                """,
                        shopLocation,
                        batchId,
                        MPCS,
                        localSkuId,
                        Timestamp.from(clock.instant()));

        // 6. Seed sales history in M8 for the local SKU
        superuserJdbc()
                .update(
                        """
                insert into reporting.shop_sale_line_fact
                    (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                values (?, ?, 1, ?, ?, ?, ?, 10.000, 1500.00)
                """,
                        Ids.next(),
                        Ids.next(),
                        MPCS,
                        shopLocation,
                        Date.valueOf(today),
                        localSkuId);

        // Check reports BEFORE merge: attributed to local SKU
        ReportTable preSales =
                reportingQueries.report("shop-sales-by-item", new ReportParameters(today, today, null), mpcsScope);
        assertThat(preSales.rows()).hasSize(1);
        assertThat(preSales.rows().get(0).get("skuCode")).isEqualTo(localSkuCode);
        assertThat(preSales.rows().get(0).get("qty")).isEqualTo("10.000");
        assertThat(preSales.rows().get(0).get("value")).isEqualTo("1500.00");

        ReportTable preStock =
                reportingQueries.report("stock-position", new ReportParameters(null, null, null), mpcsScope);
        assertThat(preStock.rows()).hasSize(1);
        assertThat(preStock.rows().get(0).get("skuCode")).isEqualTo(localSkuCode);
        assertThat(preStock.rows().get(0).get("qty")).isEqualTo("25.000");

        // 7. Perform MERGE into shared SKU
        UUID mergedResult = mergeHandler.handle(new MergeIntoShared(localSkuId, sharedSkuId), fedScope);
        assertThat(mergedResult).isEqualTo(sharedSkuId);

        // 8. Deliver event to M5 SkuMergedConsumer to re-point stock lot
        SkuMerged event = new SkuMerged(localSkuId, sharedSkuId, MPCS, localSkuCode, sharedSkuCode, "INACTIVE");
        m5SkuMergedConsumer.onSkuMerged(mapper.valueToTree(event), fedScope);

        // Verification 1: M2 Sku status and alias
        String localStatus = superuserJdbc()
                .queryForObject("select status from catalogue.sku where sku_id = ?", String.class, localSkuId);
        assertThat(localStatus).isEqualTo("INACTIVE");

        UUID resolvedCanonical = catalogueQueries.resolveAlias(localSkuId, mpcsScope);
        assertThat(resolvedCanonical).isEqualTo(sharedSkuId);

        // Verification 2: M2 Barcode moved to shared SKU
        UUID barcodeSku = superuserJdbc()
                .queryForObject("select sku_id from catalogue.sku_barcode where barcode = '4791000888001'", UUID.class);
        assertThat(barcodeSku).isEqualTo(sharedSkuId);

        // Verification 3: M5 Lot re-pointed to shared SKU
        UUID updatedLotSku = superuserJdbc()
                .queryForObject("select sku_id from inventory.stock_lot where stock_lot_id = ?", UUID.class, lotId);
        assertThat(updatedLotSku).isEqualTo(sharedSkuId);

        // Verification 4: M8 Sales history follows the new shared SKU
        ReportTable postSales =
                reportingQueries.report("shop-sales-by-item", new ReportParameters(today, today, null), mpcsScope);
        assertThat(postSales.rows()).hasSize(1);
        assertThat(postSales.rows().get(0).get("skuCode")).isEqualTo(sharedSkuCode);
        assertThat(postSales.rows().get(0).get("skuName")).isEqualTo("Shared Ceylon Tea");
        assertThat(postSales.rows().get(0).get("qty")).isEqualTo("10.000");
        assertThat(postSales.rows().get(0).get("value")).isEqualTo("1500.00");

        // Verification 5: M8 Stock position follows the new shared SKU
        ReportTable postStock =
                reportingQueries.report("stock-position", new ReportParameters(null, null, null), mpcsScope);
        assertThat(postStock.rows()).hasSize(1);
        assertThat(postStock.rows().get(0).get("skuCode")).isEqualTo(sharedSkuCode);
        assertThat(postStock.rows().get(0).get("skuName")).isEqualTo("Shared Ceylon Tea");
        assertThat(postStock.rows().get(0).get("qty")).isEqualTo("25.000");
    }

    private static ScopeContext scopeOf(UUID entity) {
        Scope s = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(s),
                s,
                PolicyClass.OWN,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    private static SkuDetails details(String en, String si, String ta, String uom) {
        return new SkuDetails(
                en,
                si,
                ta,
                null,
                null,
                null,
                uom,
                false,
                false,
                false,
                false,
                null,
                TAX_CATEGORY,
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of());
    }
}
