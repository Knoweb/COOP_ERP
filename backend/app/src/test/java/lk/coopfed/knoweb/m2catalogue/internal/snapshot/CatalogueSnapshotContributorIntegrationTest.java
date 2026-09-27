package lk.coopfed.knoweb.m2catalogue.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M2's snapshot contributor (22A section 7.3) in the scope of a till: the SKUs the shop's entity
 * may sell (its own LOCAL ones and every SHARED one) each with its conversions, ACTIVE barcodes
 * and tags inside the row, and the tax categories with their rates; decimals as text.
 */
class CatalogueSnapshotContributorIntegrationTest extends PostgresIntegrationTest {

    static final UUID ENTITY = UUID.fromString("0190a820-0000-7000-8000-000000000001");
    static final UUID OTHER_ENTITY = UUID.fromString("0190a820-0000-7000-8000-000000000002");
    static final UUID SHOP = UUID.fromString("0190a820-0000-7000-8000-000000000101");
    static final UUID LOCAL = UUID.fromString("0190a820-0000-7000-8000-000000000301");
    static final UUID DRAFT = UUID.fromString("0190a820-0000-7000-8000-000000000302");
    static final UUID OTHERS = UUID.fromString("0190a820-0000-7000-8000-000000000303");
    static final UUID SHARED = UUID.fromString("0190a820-0000-7000-8000-000000000304");
    static final UUID TAX = UUID.fromString("0190a820-0000-7000-8000-000000000901");

    @Autowired
    CatalogueSnapshotContributor catalogue;

    @Autowired
    SystemScope transactions;

    private final Shop shop = new Shop(ENTITY, SHOP);
    private final ScopeContext till = SystemScope.own(ENTITY, SHOP);

    @BeforeEach
    void aCatalogue() {
        JdbcTemplate db = superuserJdbc();
        forget(db);
        sku(db, LOCAL, "M2SNAP1", ENTITY, "LOCAL");
        sku(db, DRAFT, "M2SNAP2", ENTITY, "DRAFT");
        sku(db, OTHERS, "M2SNAP3", OTHER_ENTITY, "LOCAL");
        sku(db, SHARED, "M2SNAP4", TEST_FEDERATION, "SHARED");
        db.update(
                """
                insert into catalogue.sku_uom_conversion (sku_id, uom_code, factor_to_base, effective_from, effective_to, owner_entity_id)
                values (?, 'EA', 1, '2024-01-01', '2024-12-31', ?),
                       (?, 'EA', 1.5, '2025-01-01', null, ?)
                """,
                LOCAL,
                ENTITY,
                LOCAL,
                ENTITY);
        db.update(
                """
                insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)
                values ('M2SNAP-0001', 'INTERNAL', ?, 'EA', ?, 'ACTIVE'),
                       ('M2SNAP-0002', 'INTERNAL', ?, 'EA', ?, 'RETIRED')
                """,
                LOCAL,
                ENTITY,
                LOCAL,
                ENTITY);
        db.update(
                "insert into catalogue.sku_tag (sku_id, tag_code, owner_entity_id) values (?, 'rice', ?)",
                LOCAL,
                ENTITY);
    }

    @AfterEach
    void forgetAfterwards() {
        forget(superuserJdbc());
    }

    @Test
    void theTillGetsTheSkusItsEntityMaySellAndNoOthers() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(till, () -> catalogue.allRows("sku", shop));

        assertThat(rows).containsKeys(LOCAL, SHARED).doesNotContainKeys(DRAFT, OTHERS);
    }

    @Test
    void aSkuRowIsTheWholeItemWithItsConversionsBarcodesAndTags() {
        Map<UUID, Map<String, Object>> rows =
                transactions.inScope(till, () -> catalogue.rows("sku", shop, List.of(LOCAL, DRAFT)));

        // The DRAFT one is not in the snapshot: the builder sends a tombstone for it.
        assertThat(rows).containsOnlyKeys(LOCAL);
        Map<String, Object> item = rows.get(LOCAL);
        assertThat(item).containsEntry("sku_code", "M2SNAP1").containsEntry("status", "LOCAL");
        // Only the conversion still in force; the factor is text.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversions = (List<Map<String, Object>>) item.get("conversions");
        assertThat(conversions).hasSize(1);
        assertThat(conversions.getFirst())
                .containsEntry("uom_code", "EA")
                .containsEntry("factor_to_base", "1.500000")
                .containsEntry("effective_from", "2025-01-01")
                .containsEntry("effective_to", null);
        assertThat(item.get("barcodes").toString()).contains("M2SNAP-0001").doesNotContain("M2SNAP-0002");
        assertThat(item.get("tags")).isEqualTo(List.of("rice"));
    }

    @Test
    void aSkuRowCarriesTheThumbnailTheShopShowsItsOwnOverrideFirst() {
        // M2-06: the Federation's image of the SHARED item, this entity's override, another
        // entity's override, and a RETIRED one.
        JdbcTemplate db = superuserJdbc();
        image(db, SHARED, TEST_FEDERATION, "ACTIVE", "fed/thumb.png");
        image(db, SHARED, ENTITY, "ACTIVE", "own/thumb.png");
        image(db, SHARED, OTHER_ENTITY, "ACTIVE", "other/thumb.png");
        image(db, LOCAL, ENTITY, "RETIRED", "old/thumb.png");

        Map<UUID, Map<String, Object>> rows =
                transactions.inScope(till, () -> catalogue.rows("sku", shop, List.of(SHARED, LOCAL)));

        assertThat(rows.get(SHARED).get("images").toString())
                .contains("own/thumb.png")
                .doesNotContain("fed/thumb.png")
                .doesNotContain("other/thumb.png");
        assertThat(rows.get(LOCAL).get("images")).isEqualTo(List.of());

        // Another entity's shop sees the Federation's image, never this entity's override.
        Map<UUID, Map<String, Object>> elsewhere = transactions.inScope(
                SystemScope.own(TEST_FEDERATION, null),
                () -> catalogue.rows("sku", new Shop(TEST_FEDERATION, null), List.of(SHARED)));
        assertThat(elsewhere.get(SHARED).get("images").toString())
                .contains("fed/thumb.png")
                .doesNotContain("own/thumb.png");
    }

    @Test
    void theTaxCategoriesWithTheirRates() {
        superuserJdbc()
                .update(
                        "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id) values (?, 'M2SNAP', 'Snapshot tax', ?)",
                        TAX,
                        TEST_FEDERATION);
        superuserJdbc()
                .update(
                        """
                        insert into catalogue.tax_rate (tax_category_id, rate_percent, effective_from, effective_to, owner_entity_id)
                        values (?, 10, '2020-01-01', '2020-12-31', ?), (?, 12.5, '2021-01-01', null, ?)
                        """,
                        TAX,
                        TEST_FEDERATION,
                        TAX,
                        TEST_FEDERATION);

        Map<UUID, Map<String, Object>> rows =
                transactions.inScope(till, () -> catalogue.rows("tax_category", shop, List.of(TAX)));

        assertThat(rows.get(TAX)).containsEntry("code", "M2SNAP");
        assertThat(rows.get(TAX).get("rates").toString()).contains("12.50").doesNotContain("10.00");
    }

    private static void sku(JdbcTemplate db, UUID id, String code, UUID owner, String status) {
        db.update(
                """
                insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                                           base_uom_code, tax_category_id)
                values (?, ?, ?, ?, ?, 'සිංහල', 'தமிழ்', 'EA', ?)
                """,
                id,
                code,
                owner,
                status,
                "Snapshot item " + code,
                TAX);
    }

    private static void image(JdbcTemplate db, UUID sku, UUID owner, String status, String thumb) {
        db.update(
                """
                insert into catalogue.sku_image (image_id, sku_id, owner_entity_id, object_key_full, object_key_thumb,
                                                 content_hash, content_type, status, upload_expires_at)
                values (gen_random_uuid(), ?, ?, 'objects/m2catalogue/x', ?, repeat('a', 64), 'image/png', ?, now())
                """,
                sku,
                owner,
                thumb,
                status);
    }

    private static void forget(JdbcTemplate db) {
        List<UUID> skus = List.of(LOCAL, DRAFT, OTHERS, SHARED);
        for (UUID sku : skus) {
            db.update("delete from catalogue.sku_image where sku_id = ?", sku);
            db.update("delete from catalogue.sku_tag where sku_id = ?", sku);
            db.update("delete from catalogue.sku_barcode where sku_id = ?", sku);
            db.update("delete from catalogue.sku_uom_conversion where sku_id = ?", sku);
            db.update("delete from catalogue.sku where sku_id = ?", sku);
        }
        db.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX);
        db.update("delete from catalogue.tax_category where tax_category_id = ?", TAX);
    }
}
