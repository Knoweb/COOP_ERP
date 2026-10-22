package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.m2catalogue.internal.assortment.AssortmentMaintainer;
import lk.coopfed.knoweb.m2catalogue.internal.assortment.AssortmentReconcileJob;
import lk.coopfed.knoweb.m2catalogue.internal.assortment.AssortmentRecord;
import lk.coopfed.knoweb.m2catalogue.internal.assortment.AssortmentStore;
import lk.coopfed.knoweb.m2catalogue.internal.snapshot.CatalogueSnapshotContributor;
import lk.coopfed.knoweb.m2catalogue.query.AssortmentQueries;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.LocationAssortmentView;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task CAT-06 Integration Tests:
 * <ul>
 *   <li>Each till strictly receives only its designated assortment via snapshot sync.
 *   <li>A deactivated SKU with remaining stock continues to sell until completely sold out.
 *   <li>Event consumers dynamically maintain the location assortment.
 *   <li>Nightly reconcile job detects drift, repairs it, and commits ASSORTMENT_DRIFT audit events.
 * </ul>
 */
class AssortmentMaintainerIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS_A = UUID.fromString("0190e640-0000-7000-8000-0000000000a1");
    private static final UUID LOC_A = UUID.fromString("0190e640-0000-7000-8000-0000000001a1");

    private static final UUID MPCS_B = UUID.fromString("0190e640-0000-7000-8000-0000000000b1");
    private static final UUID LOC_B = UUID.fromString("0190e640-0000-7000-8000-0000000001b1");

    private static final UUID SKU_LOCAL_A = UUID.fromString("0190e640-0000-7000-8000-000000000301");
    private static final UUID SKU_LOCAL_B = UUID.fromString("0190e640-0000-7000-8000-000000000302");
    private static final UUID SKU_SHARED_COMMON = UUID.fromString("0190e640-0000-7000-8000-000000000303");
    private static final UUID SKU_SHARED_A_ONLY = UUID.fromString("0190e640-0000-7000-8000-000000000304");
    private static final UUID SKU_UNASSIGNED = UUID.fromString("0190e640-0000-7000-8000-000000000305");

    private static final UUID BATCH_A = UUID.fromString("0190e640-0000-7000-8000-000000000401");

    @Autowired
    private AssortmentMaintainer maintainer;

    @Autowired
    private AssortmentReconcileJob reconcileJob;

    @Autowired
    private AssortmentQueries assortmentQueries;

    @Autowired
    private CatalogueQueries catalogueQueries;

    @Autowired
    private CatalogueSnapshotContributor snapshotContributor;

    @Autowired
    private AssortmentStore store;

    @Autowired
    private SystemScope transactions;

    private final ObjectMapper json = new ObjectMapper();
    private final Shop shopA = new Shop(MPCS_A, LOC_A);
    private final Shop shopB = new Shop(MPCS_B, LOC_B);
    private final ScopeContext scopeA = SystemScope.own(MPCS_A, LOC_A);
    private final ScopeContext scopeB = SystemScope.own(MPCS_B, LOC_B);

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate db = superuserJdbc();

        // 1. Entities & Locations
        db.update(
                """
                insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status)
                values (?, 'MPCS', 'MPCS-A', 'MPCS A', 'ACTIVE'),
                       (?, 'MPCS', 'MPCS-B', 'MPCS B', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                MPCS_A,
                MPCS_B);

        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status)
                values (?, ?, 'SHOP-A', 'SHOP', 'Shop A', 'en', 'ACTIVE'),
                       (?, ?, 'SHOP-B', 'SHOP', 'Shop B', 'en', 'ACTIVE')
                on conflict (location_id) do nothing
                """,
                LOC_A,
                MPCS_A,
                LOC_B,
                MPCS_B);

        // 2. Base units
        db.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");

        // 3. SKUs
        insertSku(db, SKU_LOCAL_A, "SKU-LOC-A", MPCS_A, "LOCAL");
        insertSku(db, SKU_LOCAL_B, "SKU-LOC-B", MPCS_B, "LOCAL");
        insertSku(db, SKU_SHARED_COMMON, "SKU-SH-COM", TEST_FEDERATION, "SHARED");
        insertSku(db, SKU_SHARED_A_ONLY, "SKU-SH-A", TEST_FEDERATION, "SHARED");
        insertSku(db, SKU_UNASSIGNED, "SKU-UNASSIGN", TEST_FEDERATION, "SHARED");

        // Barcode for SKU_LOCAL_A
        db.update(
                """
                insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)
                values ('4791234500011', 'EAN13', ?, 'EA', ?, 'ACTIVE')
                """,
                SKU_LOCAL_A,
                MPCS_A);

        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate db = superuserJdbc();
        db.execute(
                "truncate catalogue.location_assortment, catalogue.sku_barcode, catalogue.sku_uom_conversion, catalogue.sku cascade");
        db.update("delete from inventory.stock_lot where location_id in (?, ?)", LOC_A, LOC_B);
    }

    @Test
    void eachTillStrictlyReceivesOnlyItsDesignatedAssortment() {
        // Arrange designated assortments:
        // Shop A gets: SKU_LOCAL_A (LOCAL), SKU_SHARED_COMMON (CORE_RANGE), SKU_SHARED_A_ONLY (RETAIL_LIST)
        // Shop B gets: SKU_LOCAL_B (LOCAL), SKU_SHARED_COMMON (CORE_RANGE)
        // SKU_UNASSIGNED is in neither assortment.
        maintainer.upsert(LOC_A, SKU_LOCAL_A, List.of("LOCAL"), false, MPCS_A, scopeA);
        maintainer.upsert(LOC_A, SKU_SHARED_COMMON, List.of("CORE_RANGE"), false, MPCS_A, scopeA);
        maintainer.upsert(LOC_A, SKU_SHARED_A_ONLY, List.of("RETAIL_LIST"), false, MPCS_A, scopeA);

        maintainer.upsert(LOC_B, SKU_LOCAL_B, List.of("LOCAL"), false, MPCS_B, scopeB);
        maintainer.upsert(LOC_B, SKU_SHARED_COMMON, List.of("CORE_RANGE"), false, MPCS_B, scopeB);

        // Act: Till A downloads full snapshot
        Map<UUID, Map<String, Object>> rowsA =
                transactions.inScope(scopeA, () -> snapshotContributor.allRows("sku", shopA));

        // Act: Till B downloads full snapshot
        Map<UUID, Map<String, Object>> rowsB =
                transactions.inScope(scopeB, () -> snapshotContributor.allRows("sku", shopB));

        // Assert: Till A strictly receives its 3 items
        assertThat(rowsA).containsOnlyKeys(SKU_LOCAL_A, SKU_SHARED_COMMON, SKU_SHARED_A_ONLY);
        assertThat(rowsA).doesNotContainKeys(SKU_LOCAL_B, SKU_UNASSIGNED);

        // Assert: Till B strictly receives its 2 items
        assertThat(rowsB).containsOnlyKeys(SKU_LOCAL_B, SKU_SHARED_COMMON);
        assertThat(rowsB).doesNotContainKeys(SKU_LOCAL_A, SKU_SHARED_A_ONLY, SKU_UNASSIGNED);
    }

    @Test
    void aDeactivatedSkuWithRemainingStockContinuesToSellUntilCompletelySoldOut() {
        JdbcTemplate db = superuserJdbc();

        // 1. Arrange SKU in assortment with initial stock
        maintainer.upsert(LOC_A, SKU_LOCAL_A, List.of("LOT"), false, MPCS_A, scopeA);
        db.update(
                """
                insert into inventory.stock_lot (
                    stock_lot_id, owner_entity_id, location_id, sku_id, batch_id, condition, qty_on_hand, unit_cost
                ) values (gen_random_uuid(), ?, ?, ?, ?, 'GOOD', 15.000, 100.00)
                """,
                MPCS_A,
                LOC_A,
                SKU_LOCAL_A,
                BATCH_A);

        // Initial scan: active item sells normally with sellThrough = false
        Optional<LookupResult> beforeDeactivation = transactions.inScope(
                scopeA,
                () -> catalogueQueries.lookupByBarcode(
                        new BarcodeLookup("4791234500011", null, null, null, "EAN13", LOC_A), scopeA));
        assertThat(beforeDeactivation).isPresent();
        assertThat(beforeDeactivation.get().sellThrough()).isFalse();

        // 2. Deactivate the SKU
        db.update("update catalogue.sku set status = 'INACTIVE' where sku_id = ?", SKU_LOCAL_A);
        ObjectNode deactivatedEvent = json.createObjectNode()
                .put("skuId", SKU_LOCAL_A.toString())
                .put("ownerEntityId", MPCS_A.toString())
                .put("priorStatus", "LOCAL");
        maintainer.onSkuDeactivated(deactivatedEvent, scopeA);

        // Assert: Assortment row remains with sell_through = true
        Optional<LocationAssortmentView> item =
                transactions.inScope(scopeA, () -> assortmentQueries.getAssortmentItem(LOC_A, SKU_LOCAL_A, scopeA));
        assertThat(item).isPresent();
        assertThat(item.get().sellThrough()).isTrue();

        // Assert: Till snapshot still contains the deactivated item with sell_through = true
        Map<UUID, Map<String, Object>> snapshotRows =
                transactions.inScope(scopeA, () -> snapshotContributor.allRows("sku", shopA));
        assertThat(snapshotRows).containsKey(SKU_LOCAL_A);
        assertThat(snapshotRows.get(SKU_LOCAL_A))
                .containsEntry("status", "INACTIVE")
                .containsEntry("sell_through", true);

        // Assert: Till barcode scan succeeds with sellThrough = true
        Optional<LookupResult> duringSellThrough = transactions.inScope(
                scopeA,
                () -> catalogueQueries.lookupByBarcode(
                        new BarcodeLookup("4791234500011", null, null, null, "EAN13", LOC_A), scopeA));
        assertThat(duringSellThrough).isPresent();
        assertThat(duringSellThrough.get().sellThrough()).isTrue();

        // 3. Sell out remaining stock down to 0
        db.update(
                "update inventory.stock_lot set qty_on_hand = 0 where location_id = ? and sku_id = ?",
                LOC_A,
                SKU_LOCAL_A);
        ObjectNode stockDepletedEvent = json.createObjectNode()
                .put("locationId", LOC_A.toString())
                .put("skuId", SKU_LOCAL_A.toString())
                .put("ownerEntityId", MPCS_A.toString())
                .put("qtyDelta", -15.000)
                .put("lotQtyOnHand", 0.000);
        maintainer.onStockChanged(stockDepletedEvent, scopeA);

        // Assert: Assortment row is now completely removed
        Optional<LocationAssortmentView> itemAfterDepletion =
                transactions.inScope(scopeA, () -> assortmentQueries.getAssortmentItem(LOC_A, SKU_LOCAL_A, scopeA));
        assertThat(itemAfterDepletion).isEmpty();

        // Assert: Till snapshot no longer serves the item
        Map<UUID, Map<String, Object>> snapshotAfter =
                transactions.inScope(scopeA, () -> snapshotContributor.allRows("sku", shopA));
        assertThat(snapshotAfter).doesNotContainKey(SKU_LOCAL_A);

        // Assert: Till barcode lookup now fails because the item is completely sold out
        Optional<LookupResult> scanAfterSoldOut = transactions.inScope(
                scopeA,
                () -> catalogueQueries.lookupByBarcode(
                        new BarcodeLookup("4791234500011", null, null, null, "EAN13", LOC_A), scopeA));
        assertThat(scanAfterSoldOut).isEmpty();
    }

    @Test
    void priceListPublishedEventDynamicallyUpdatesAssortment() {
        // Publish retail price list for MPCS_A containing SKU_SHARED_COMMON
        ObjectNode event = json.createObjectNode();
        event.put("kind", "RETAIL");
        event.put("ownerEntityId", MPCS_A.toString());
        ArrayNode skuIds = event.putArray("skuIds");
        skuIds.add(SKU_SHARED_COMMON.toString());

        maintainer.onPriceListPublished(event, scopeA);

        // Assert: Shop A assortment now contains SKU_SHARED_COMMON with reason RETAIL_LIST
        Optional<LocationAssortmentView> item = transactions.inScope(
                scopeA, () -> assortmentQueries.getAssortmentItem(LOC_A, SKU_SHARED_COMMON, scopeA));
        assertThat(item).isPresent();
        assertThat(item.get().reasons()).contains("RETAIL_LIST");

        // Now publish a revised price list omitting SKU_SHARED_COMMON
        ObjectNode revised = json.createObjectNode();
        revised.put("kind", "RETAIL");
        revised.put("ownerEntityId", MPCS_A.toString());
        revised.putArray("skuIds"); // empty

        maintainer.onPriceListPublished(revised, scopeA);

        // Assert: SKU_SHARED_COMMON is removed from Shop A assortment
        Optional<LocationAssortmentView> removed = transactions.inScope(
                scopeA, () -> assortmentQueries.getAssortmentItem(LOC_A, SKU_SHARED_COMMON, scopeA));
        assertThat(removed).isEmpty();
    }

    @Test
    void reconcileJobFindsDriftAndRepairsAndAudits() {
        // Arrange:
        // Inject drift:
        // 1. SKU_UNASSIGNED inserted artificially into LOC_A assortment (extraneous)
        maintainer.upsert(LOC_A, SKU_UNASSIGNED, List.of("LOT"), false, MPCS_A, scopeA);

        // 2. LOC_A has stock of SKU_LOCAL_A in DB, but it's missing from location_assortment
        superuserJdbc()
                .update(
                        """
                insert into inventory.stock_lot (
                    stock_lot_id, owner_entity_id, location_id, sku_id, batch_id, condition, qty_on_hand, unit_cost
                ) values (gen_random_uuid(), ?, ?, ?, ?, 'GOOD', 5.000, 100.00)
                """,
                        MPCS_A,
                        LOC_A,
                        SKU_LOCAL_A,
                        BATCH_A);

        kernel.reset();

        // Act: Run reconcile for LOC_A
        int drifts = reconcileJob.reconcileLocation(LOC_A, MPCS_A, scopeA);

        // Assert: Reconcile detected and fixed both drifts
        assertThat(drifts).isGreaterThanOrEqualTo(2);

        // Extraneous SKU removed
        assertThat(store.get(LOC_A, SKU_UNASSIGNED)).isEmpty();

        // Missing SKU restored with LOT and LOCAL reasons
        Optional<AssortmentRecord> restored = store.get(LOC_A, SKU_LOCAL_A);
        assertThat(restored).isPresent();
        assertThat(restored.get().reasons()).contains("LOT", "LOCAL");

        // Assert: ASSORTMENT_DRIFT audit events were committed
        assertThat(kernel.committedAudit())
                .anyMatch(a -> AssortmentReconcileJob.AUDIT_ASSORTMENT_DRIFT.equals(a.eventType()));
    }

    private static void insertSku(JdbcTemplate db, UUID id, String code, UUID owner, String status) {
        db.update(
                """
                insert into catalogue.sku (
                    sku_id, sku_code, owner_entity_id, status, short_name_en, base_uom_code
                ) values (?, ?, ?, ?, ?, 'EA')
                """,
                id,
                code,
                owner,
                status,
                code);
    }
}
