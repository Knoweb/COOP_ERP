package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotBuilder;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL RLS integration tests for M5 inventory snapshot contributors and change-log fan-out.
 */
class InventorySnapshotContributorIntegrationTest extends PostgresIntegrationTest {

    static final UUID SOCIETY_A = UUID.fromString("0190a820-0000-7000-8800-000000000001");
    static final UUID SOCIETY_B = UUID.fromString("0190a820-0000-7000-8800-000000000002");
    static final UUID SHOP_A = UUID.fromString("0190a820-0000-7000-8800-000000000101");
    static final UUID SHOP_B = UUID.fromString("0190a820-0000-7000-8800-000000000102");
    static final UUID WAREHOUSE_A = UUID.fromString("0190a820-0000-7000-8800-000000000103");
    static final UUID SHOP_A2 = UUID.fromString("0190a820-0000-7000-8800-000000000104");

    static final UUID TAX_CATEGORY = UUID.fromString("0190a820-0000-7000-8800-000000000201");
    static final UUID SKU_1 = UUID.fromString("0190a820-0000-7000-8800-000000000301");
    static final UUID SKU_2 = UUID.fromString("0190a820-0000-7000-8800-000000000302");

    static final UUID BATCH_1 = UUID.fromString("0190a820-0000-7000-8800-000000000401");
    static final UUID BATCH_2 = UUID.fromString("0190a820-0000-7000-8800-000000000402");
    static final UUID BATCH_EXPIRED = UUID.fromString("0190a820-0000-7000-8800-000000000403");
    static final UUID BATCH_NEGATIVE = UUID.fromString("0190a820-0000-7000-8800-000000000404");
    static final UUID BATCH_ZERO = UUID.fromString("0190a820-0000-7000-8800-000000000405");

    static final UUID LOT_GOOD_1 = UUID.fromString("0190a820-0000-7000-8800-000000000501");
    static final UUID LOT_GOOD_2 = UUID.fromString("0190a820-0000-7000-8800-000000000502");
    static final UUID LOT_EXPIRED = UUID.fromString("0190a820-0000-7000-8800-000000000503");
    static final UUID LOT_DAMAGED = UUID.fromString("0190a820-0000-7000-8800-000000000504");
    static final UUID LOT_NEGATIVE = UUID.fromString("0190a820-0000-7000-8800-000000000505");
    static final UUID LOT_ZERO = UUID.fromString("0190a820-0000-7000-8800-000000000506");
    static final UUID LOT_SHOP_B = UUID.fromString("0190a820-0000-7000-8800-000000000507");

    static final UUID TASK_SCHEDULED = UUID.fromString("0190a820-0000-7000-8800-000000000601");
    static final UUID TASK_COUNTING = UUID.fromString("0190a820-0000-7000-8800-000000000602");
    static final UUID TASK_CLOSED = UUID.fromString("0190a820-0000-7000-8800-000000000603");

    static final UUID TRANSFER_EXPECTED = UUID.fromString("0190a820-0000-7000-8800-000000000701");
    static final UUID TRANSFER_RECEIVED = UUID.fromString("0190a820-0000-7000-8800-000000000702");
    static final UUID TRANSFER_LINE_1 = UUID.fromString("0190a820-0000-7000-8800-000000000711");
    static final UUID TRANSFER_LINE_2 = UUID.fromString("0190a820-0000-7000-8800-000000000712");

    @Autowired
    LotSnapshotContributor lotContributor;

    @Autowired
    CountTaskSnapshotContributor countTaskContributor;

    @Autowired
    PolicySnapshotContributor policyContributor;

    @Autowired
    ExpectedTransferContributor transferContributor;

    @Autowired
    M5ChangeLogFanOut fanOut;

    @Autowired
    M5Shops shops;

    @Autowired
    SnapshotBuilder snapshotBuilder;

    @Autowired
    InventoryQueries inventoryQueries;

    @Autowired
    SystemScope transactions;

    @Autowired
    JdbcConfigRegistry config;

    @Autowired
    BusinessDay businessDay;

    @Autowired
    ObjectMapper json;

    private final Shop shopA = new Shop(SOCIETY_A, SHOP_A);
    private final Shop shopB = new Shop(SOCIETY_B, SHOP_B);
    private final Shop shopA2 = new Shop(SOCIETY_A, SHOP_A2);
    private final ScopeContext tillScopeA = SystemScope.own(SOCIETY_A, SHOP_A);
    private final ScopeContext tillScopeB = SystemScope.own(SOCIETY_B, SHOP_B);
    private final ScopeContext tillScopeA2 = SystemScope.own(SOCIETY_A, SHOP_A2);

    @BeforeEach
    void setupFixtures() {
        JdbcTemplate db = superuserJdbc();
        clean(db);

        // 1. Entities & Locations
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'MPCS-A', 'Society A', 'ACTIVE')",
                SOCIETY_A);
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'MPCS-B', 'Society B', 'ACTIVE')",
                SOCIETY_B);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status, size_band) values (?, ?, 'S-A', 'SHOP', 'Shop A', 'en', 'ACTIVE', 'M')",
                SHOP_A,
                SOCIETY_A);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status, size_band) values (?, ?, 'S-B', 'SHOP', 'Shop B', 'en', 'ACTIVE', 'M')",
                SHOP_B,
                SOCIETY_B);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status, size_band) values (?, ?, 'S-A2', 'SHOP', 'Shop A2', 'en', 'ACTIVE', 'M')",
                SHOP_A2,
                SOCIETY_A);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status, size_band) values (?, ?, 'WH-A', 'WAREHOUSE', 'Warehouse A', 'en', 'ACTIVE', 'L')",
                WAREHOUSE_A,
                SOCIETY_A);

        // 2. SKUs & Batches
        db.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        db.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id) values (?, 'DEFAULT_TAX', 'Default Tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                TEST_FEDERATION);
        db.update(
                "insert into catalogue.sku (sku_id, sku_code, status, short_name_en, short_name_si, short_name_ta, base_uom_code, owner_entity_id, tax_category_id) values (?, 'SKU1', 'SHARED', 'Item 1', 'Item 1 SI', 'Item 1 TA', 'EA', ?, ?)",
                SKU_1,
                TEST_FEDERATION,
                TAX_CATEGORY);
        db.update(
                "insert into catalogue.sku (sku_id, sku_code, status, short_name_en, short_name_si, short_name_ta, base_uom_code, owner_entity_id, tax_category_id) values (?, 'SKU2', 'SHARED', 'Item 2', 'Item 2 SI', 'Item 2 TA', 'EA', ?, ?)",
                SKU_2,
                TEST_FEDERATION,
                TAX_CATEGORY);

        LocalDate today = businessDay.today();
        LocalDate future1 = today.plusDays(30);
        LocalDate future2 = today.plusDays(60);
        LocalDate past = today.minusDays(10);

        db.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id) values (?, ?, 'B1', ?, 500.00, ?)",
                BATCH_1,
                SKU_1,
                Date.valueOf(future1),
                SOCIETY_A);
        db.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id) values (?, ?, 'B2', ?, 500.00, ?)",
                BATCH_2,
                SKU_1,
                Date.valueOf(future2),
                SOCIETY_A);
        db.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id) values (?, ?, 'B-EXP', ?, 500.00, ?)",
                BATCH_EXPIRED,
                SKU_1,
                Date.valueOf(past),
                SOCIETY_A);
        db.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id) values (?, ?, 'B-NEG', ?, 500.00, ?)",
                BATCH_NEGATIVE,
                SKU_1,
                Date.valueOf(future2),
                SOCIETY_A);
        db.update(
                "insert into catalogue.batch (batch_id, sku_id, batch_no, expiry_date, printed_mrp, owner_entity_id) values (?, ?, 'B-ZERO', ?, 500.00, ?)",
                BATCH_ZERO,
                SKU_1,
                Date.valueOf(future1),
                SOCIETY_A);

        // 3. Stock Lots at Shop A
        // LOT_GOOD_1: expiry future1 -> FEFO rank 1
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'GOOD', 50.000, 400.0000, now())",
                LOT_GOOD_1,
                SOCIETY_A,
                SHOP_A,
                BATCH_1,
                SKU_1,
                Date.valueOf(future1));
        // LOT_GOOD_2: expiry future2 -> FEFO rank 2
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'GOOD', 25.000, 410.0000, now())",
                LOT_GOOD_2,
                SOCIETY_A,
                SHOP_A,
                BATCH_2,
                SKU_1,
                Date.valueOf(future2));
        // LOT_EXPIRED: expiry past -> FEFO rank null
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'GOOD', 5.000, 400.0000, now())",
                LOT_EXPIRED,
                SOCIETY_A,
                SHOP_A,
                BATCH_EXPIRED,
                SKU_1,
                Date.valueOf(past));
        // LOT_DAMAGED: condition DAMAGED -> FEFO rank null
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'DAMAGED', 3.000, 400.0000, now())",
                LOT_DAMAGED,
                SOCIETY_A,
                SHOP_A,
                BATCH_1,
                SKU_1,
                Date.valueOf(future1));
        // LOT_NEGATIVE: qty -2.000 -> FEFO rank null, negative = true
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'GOOD', -2.000, 400.0000, now())",
                LOT_NEGATIVE,
                SOCIETY_A,
                SHOP_A,
                BATCH_NEGATIVE,
                SKU_1,
                Date.valueOf(future2));
        // LOT_ZERO: qty 0.000 -> excluded
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'GOOD', 0.000, 400.0000, now())",
                LOT_ZERO,
                SOCIETY_A,
                SHOP_A,
                BATCH_ZERO,
                SKU_1,
                Date.valueOf(future1));
        // LOT_SHOP_B: at Shop B -> isolated
        db.update(
                "insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, expiry_date, condition, qty_on_hand, unit_cost, received_at) values (?, ?, ?, ?, ?, ?, 'GOOD', 10.000, 400.0000, now())",
                LOT_SHOP_B,
                SOCIETY_B,
                SHOP_B,
                BATCH_1,
                SKU_1,
                Date.valueOf(future1));

        // 4. Count Tasks
        db.update(
                "insert into inventory.count_task (task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, scheduled_for, status, scheduled_at) values (?, ?, ?, 'FULL', '{}', ?, 'SCHEDULED', now())",
                TASK_SCHEDULED,
                SOCIETY_A,
                SHOP_A2,
                Date.valueOf(today));
        db.update(
                "insert into inventory.count_task (task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, scheduled_for, status, scheduled_at, started_at) values (?, ?, ?, 'SKUS', array[?::uuid], ?, 'COUNTING', now(), now())",
                TASK_COUNTING,
                SOCIETY_A,
                SHOP_A,
                SKU_1,
                Date.valueOf(today));
        db.update(
                "insert into inventory.count_expectation (task_id, owner_entity_id, location_id, batch_id, sku_id, condition, expected_qty) values (?, ?, ?, ?, ?, 'GOOD', 50.000)",
                TASK_COUNTING,
                SOCIETY_A,
                SHOP_A,
                BATCH_1,
                SKU_1);
        db.update(
                "insert into inventory.count_task (task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, scheduled_for, status, scheduled_at, started_at, submitted_at, outcome) values (?, ?, ?, 'FULL', '{}', ?, 'CLOSED', now(), now(), now(), 'POSTED')",
                TASK_CLOSED,
                SOCIETY_A,
                SHOP_A,
                Date.valueOf(today));

        // 5. Transfers: From Warehouse A to Shop A
        db.update(
                "insert into inventory.transfer (transfer_id, owner_entity_id, location_id, to_location_id, issued_at) values (?, ?, ?, ?, now())",
                TRANSFER_EXPECTED,
                SOCIETY_A,
                WAREHOUSE_A,
                SHOP_A);
        db.update(
                "insert into inventory.transfer_line (line_id, transfer_id, owner_entity_id, location_id, to_location_id, line_no, batch_id, sku_id, qty, unit_cost) values (?, ?, ?, ?, ?, 1, ?, ?, 20.000, 395.0000)",
                TRANSFER_LINE_1,
                TRANSFER_EXPECTED,
                SOCIETY_A,
                WAREHOUSE_A,
                SHOP_A,
                BATCH_1,
                SKU_1);

        // Already received transfer:
        db.update(
                "insert into inventory.transfer (transfer_id, owner_entity_id, location_id, to_location_id, issued_at) values (?, ?, ?, ?, now() - interval '1 hour')",
                TRANSFER_RECEIVED,
                SOCIETY_A,
                WAREHOUSE_A,
                SHOP_A);
        db.update(
                "insert into inventory.transfer_line (line_id, transfer_id, owner_entity_id, location_id, to_location_id, line_no, batch_id, sku_id, qty, unit_cost) values (?, ?, ?, ?, ?, 1, ?, ?, 10.000, 395.0000)",
                TRANSFER_LINE_2,
                TRANSFER_RECEIVED,
                SOCIETY_A,
                WAREHOUSE_A,
                SHOP_A,
                BATCH_2,
                SKU_1);
        db.update(
                "insert into inventory.transfer_receipt (transfer_id, owner_entity_id, location_id, from_location_id, received_at) values (?, ?, ?, ?, now())",
                TRANSFER_RECEIVED,
                SOCIETY_A,
                SHOP_A,
                WAREHOUSE_A);
    }

    @AfterEach
    void cleanAfterwards() {
        clean(superuserJdbc());
    }

    private void clean(JdbcTemplate db) {
        db.update(
                "delete from inventory.transfer_receipt where transfer_id in (?, ?)",
                TRANSFER_EXPECTED,
                TRANSFER_RECEIVED);
        db.update(
                "delete from inventory.transfer_line where transfer_id in (?, ?)",
                TRANSFER_EXPECTED,
                TRANSFER_RECEIVED);
        db.update("delete from inventory.transfer where transfer_id in (?, ?)", TRANSFER_EXPECTED, TRANSFER_RECEIVED);

        db.update(
                "delete from inventory.count_line where task_id in (?, ?, ?)",
                TASK_SCHEDULED,
                TASK_COUNTING,
                TASK_CLOSED);
        db.update(
                "delete from inventory.count_expectation where task_id in (?, ?, ?)",
                TASK_SCHEDULED,
                TASK_COUNTING,
                TASK_CLOSED);
        db.update(
                "delete from inventory.count_task where task_id in (?, ?, ?)",
                TASK_SCHEDULED,
                TASK_COUNTING,
                TASK_CLOSED);

        db.update(
                "delete from inventory.stock_lot where stock_lot_id in (?, ?, ?, ?, ?, ?, ?)",
                LOT_GOOD_1,
                LOT_GOOD_2,
                LOT_EXPIRED,
                LOT_DAMAGED,
                LOT_NEGATIVE,
                LOT_ZERO,
                LOT_SHOP_B);

        db.update(
                "delete from catalogue.batch where batch_id in (?, ?, ?, ?, ?)",
                BATCH_1,
                BATCH_2,
                BATCH_EXPIRED,
                BATCH_NEGATIVE,
                BATCH_ZERO);
        db.update("delete from catalogue.batch_key where sku_id in (?, ?)", SKU_1, SKU_2);
        db.update("delete from catalogue.sku where sku_id in (?, ?)", SKU_1, SKU_2);
        db.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);

        db.update("delete from kernel.change_log where owner_entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        db.update("delete from kernel.location_snapshot_version where owner_entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);

        db.update("delete from party.location where location_id in (?, ?, ?, ?)", SHOP_A, SHOP_B, SHOP_A2, WAREHOUSE_A);
        db.update("delete from party.entity_party_directory where entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        db.update("delete from party.entity where entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
    }

    // ===========================================================================================
    // 1. Contributor Table Declarations
    // ===========================================================================================

    @Test
    void contributorsDeclareExactUniqueTableNames() {
        assertThat(lotContributor.tables()).containsExactly("lot");
        assertThat(countTaskContributor.tables()).containsExactly("count_task");
        assertThat(policyContributor.tables()).containsExactly("inventory_policy");
        assertThat(transferContributor.tables()).containsExactly("expected_transfer");
    }

    // ===========================================================================================
    // 2. Lot Snapshot Contributor
    // ===========================================================================================

    @Test
    void fullSnapshotDeliversLotsWithFefoRankAndExcludesZeroAndCosts() {
        Map<UUID, Map<String, Object>> lots =
                transactions.inScope(tillScopeA, () -> lotContributor.allRows("lot", shopA));

        // Includes GOOD 1, GOOD 2, EXPIRED, DAMAGED, NEGATIVE. Excludes ZERO and Shop B lot.
        assertThat(lots).containsKeys(LOT_GOOD_1, LOT_GOOD_2, LOT_EXPIRED, LOT_DAMAGED, LOT_NEGATIVE);
        assertThat(lots).doesNotContainKeys(LOT_ZERO, LOT_SHOP_B);

        // LOT_GOOD_1 (earlier expiry) has rank 1
        Map<String, Object> good1 = lots.get(LOT_GOOD_1);
        assertThat(good1.get("stock_lot_id")).isEqualTo(LOT_GOOD_1.toString());
        assertThat(good1.get("batch_id")).isEqualTo(BATCH_1.toString());
        assertThat(good1.get("sku_id")).isEqualTo(SKU_1.toString());
        assertThat(good1.get("condition")).isEqualTo("GOOD");
        assertThat(good1.get("qty_on_hand")).isEqualTo("50.000");
        assertThat(good1.get("fefo_rank")).isEqualTo(1);
        assertThat(good1.get("negative")).isEqualTo(false);
        assertThat(good1).doesNotContainKeys("unit_cost", "cost", "margin");

        // LOT_GOOD_2 (later expiry) has rank 2
        Map<String, Object> good2 = lots.get(LOT_GOOD_2);
        assertThat(good2.get("fefo_rank")).isEqualTo(2);

        // LOT_EXPIRED has null fefo_rank
        Map<String, Object> expired = lots.get(LOT_EXPIRED);
        assertThat(expired.get("fefo_rank")).isNull();
        assertThat(expired.get("negative")).isEqualTo(false);

        // LOT_DAMAGED has null fefo_rank
        Map<String, Object> damaged = lots.get(LOT_DAMAGED);
        assertThat(damaged.get("condition")).isEqualTo("DAMAGED");
        assertThat(damaged.get("fefo_rank")).isNull();

        // LOT_NEGATIVE has negative = true and null fefo_rank
        Map<String, Object> negative = lots.get(LOT_NEGATIVE);
        assertThat(negative.get("qty_on_hand")).isEqualTo("-2.000");
        assertThat(negative.get("negative")).isEqualTo(true);
        assertThat(negative.get("fefo_rank")).isNull();
    }

    @Test
    void fefoRankParityWithAuthoritativeInventoryQueries() {
        Map<UUID, Map<String, Object>> snapshotLots =
                transactions.inScope(tillScopeA, () -> lotContributor.allRows("lot", shopA));

        List<LotBalance> balances =
                transactions.inScope(tillScopeA, () -> inventoryQueries.balances(SHOP_A, SKU_1, true, tillScopeA));

        for (LotBalance balance : balances) {
            if (balance.qtyOnHand().compareTo(BigDecimal.ZERO) == 0) {
                assertThat(snapshotLots).doesNotContainKey(balance.stockLotId());
            } else {
                Map<String, Object> row = snapshotLots.get(balance.stockLotId());
                assertThat(row).isNotNull();
                assertThat(row.get("fefo_rank")).isEqualTo(balance.fefoRank());
            }
        }
    }

    @Test
    void deltaRowQueriesExcludeZeroQuantityToTriggerTombstones() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(
                tillScopeA, () -> lotContributor.rows("lot", shopA, List.of(LOT_GOOD_1, LOT_ZERO)));

        assertThat(rows).containsKey(LOT_GOOD_1).doesNotContainKey(LOT_ZERO);
    }

    // ===========================================================================================
    // 3. Count Task Snapshot Contributor
    // ===========================================================================================

    @Test
    void fullSnapshotDeliversOpenTasksAndExcludesClosedTasks() {
        Map<UUID, Map<String, Object>> tasksA =
                transactions.inScope(tillScopeA, () -> countTaskContributor.allRows("count_task", shopA));

        assertThat(tasksA).containsKey(TASK_COUNTING).doesNotContainKeys(TASK_CLOSED, TASK_SCHEDULED);

        // COUNTING task has populated expectation lines
        Map<String, Object> counting = tasksA.get(TASK_COUNTING);
        assertThat(counting.get("status")).isEqualTo("COUNTING");
        assertThat(counting.get("scope_kind")).isEqualTo("SKUS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> countExp = (List<Map<String, Object>>) counting.get("expectations");
        assertThat(countExp).hasSize(1);
        Map<String, Object> expLine = countExp.get(0);
        assertThat(expLine.get("batch_id")).isEqualTo(BATCH_1.toString());
        assertThat(expLine.get("sku_id")).isEqualTo(SKU_1.toString());
        assertThat(expLine.get("expected_qty")).isEqualTo("50.000");

        // Shop A2 receives TASK_SCHEDULED with empty expectations
        Map<UUID, Map<String, Object>> tasksA2 =
                transactions.inScope(tillScopeA2, () -> countTaskContributor.allRows("count_task", shopA2));
        assertThat(tasksA2).containsKey(TASK_SCHEDULED).doesNotContainKeys(TASK_CLOSED, TASK_COUNTING);

        Map<String, Object> scheduled = tasksA2.get(TASK_SCHEDULED);
        assertThat(scheduled.get("status")).isEqualTo("SCHEDULED");
        assertThat(scheduled.get("scope_kind")).isEqualTo("FULL");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schedExp = (List<Map<String, Object>>) scheduled.get("expectations");
        assertThat(schedExp).isEmpty();
    }

    @Test
    void countTaskTombstoneOnDeltaWhenTaskClosed() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(
                tillScopeA, () -> countTaskContributor.rows("count_task", shopA, List.of(TASK_COUNTING, TASK_CLOSED)));

        assertThat(rows).containsKey(TASK_COUNTING).doesNotContainKey(TASK_CLOSED);
    }

    // ===========================================================================================
    // 4. Expected Transfer Snapshot Contributor
    // ===========================================================================================

    @Test
    void fullSnapshotDeliversInTransitTransfersAndExcludesReceivedTransfers() {
        Map<UUID, Map<String, Object>> transfers =
                transactions.inScope(tillScopeA, () -> transferContributor.allRows("expected_transfer", shopA));

        assertThat(transfers).containsKey(TRANSFER_EXPECTED).doesNotContainKey(TRANSFER_RECEIVED);

        Map<String, Object> xfer = transfers.get(TRANSFER_EXPECTED);
        assertThat(xfer.get("transfer_id")).isEqualTo(TRANSFER_EXPECTED.toString());
        assertThat(xfer.get("from_location_id")).isEqualTo(WAREHOUSE_A.toString());
        assertThat(xfer.get("to_location_id")).isEqualTo(SHOP_A.toString());
        assertThat(xfer.get("issued_at")).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) xfer.get("lines");
        assertThat(lines).hasSize(1);
        Map<String, Object> line = lines.get(0);
        assertThat(line.get("line_id")).isEqualTo(TRANSFER_LINE_1.toString());
        assertThat(line.get("line_no")).isEqualTo(1);
        assertThat(line.get("batch_id")).isEqualTo(BATCH_1.toString());
        assertThat(line.get("sku_id")).isEqualTo(SKU_1.toString());
        assertThat(line.get("qty")).isEqualTo("20.000");
        assertThat(line).doesNotContainKeys("unit_cost", "cost");
    }

    @Test
    void receivedTransferIsTombstonedInDelta() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(
                tillScopeA,
                () -> transferContributor.rows(
                        "expected_transfer", shopA, List.of(TRANSFER_EXPECTED, TRANSFER_RECEIVED)));

        assertThat(rows).containsKey(TRANSFER_EXPECTED).doesNotContainKey(TRANSFER_RECEIVED);
    }

    // ===========================================================================================
    // 5. Inventory Policy Snapshot Contributor
    // ===========================================================================================

    @Test
    void fullSnapshotDeliversAll11InventoryPolicyItemsWithDefaultsAndOverrides() {
        // Set an entity override for count_tolerance_pct = "5"
        ScopeContext societyScope = SystemScope.own(SOCIETY_A, null);
        transactions.inScope(societyScope, () -> {
            config.set(
                    PolicySnapshotContributor.COUNT_TOLERANCE_PCT,
                    ConfigRegistry.ConfigScope.entity(SOCIETY_A),
                    "5",
                    societyScope,
                    "test override");
            return null;
        });

        Map<UUID, Map<String, Object>> policyMap =
                transactions.inScope(tillScopeA, () -> policyContributor.allRows("inventory_policy", shopA));

        assertThat(policyMap).containsKey(SHOP_A);
        Map<String, Object> policy = policyMap.get(SHOP_A);

        assertThat(policy.get("location_id")).isEqualTo(SHOP_A.toString());
        assertThat(policy.get("count_tolerance_pct")).isEqualTo("5"); // entity override
        assertThat(policy.get("count_tolerance_qty")).isEqualTo("2"); // default
        assertThat(policy.get("count_tolerance_value")).isEqualTo("1000");
        assertThat(policy.get("count_autopost_value_cap")).isEqualTo("10000");
        assertThat(policy.get("approval_band1_limit")).isEqualTo("25000");
        assertThat(policy.get("approval_band2_limit")).isEqualTo("250000");
        assertThat(policy.get("single_staff")).isEqualTo(false); // shop A size band M
        assertThat(policy.get("remote_witness_allowed")).isEqualTo(true);
        assertThat(policy.get("writeoff_photo_categories")).isEqualTo(List.of("THEFT", "SHRINKAGE_UNEXPLAINED"));
        assertThat(policy.get("repack_yield_tolerance_pct")).isEqualTo("2");
        assertThat(policy.get("dispatch_min_shelf_life_days")).isEqualTo(0);
    }

    // ===========================================================================================
    // 6. Cross-Tenant and Cross-Location Isolation
    // ===========================================================================================

    @Test
    void strictMultiTenantAndLocationIsolation() {
        Map<UUID, Map<String, Object>> lotsB =
                transactions.inScope(tillScopeB, () -> lotContributor.allRows("lot", shopB));

        assertThat(lotsB).containsKey(LOT_SHOP_B).doesNotContainKeys(LOT_GOOD_1, LOT_GOOD_2, LOT_EXPIRED);

        Map<UUID, Map<String, Object>> tasksB =
                transactions.inScope(tillScopeB, () -> countTaskContributor.allRows("count_task", shopB));

        assertThat(tasksB).doesNotContainKeys(TASK_SCHEDULED, TASK_COUNTING);

        Map<UUID, Map<String, Object>> transfersB =
                transactions.inScope(tillScopeB, () -> transferContributor.allRows("expected_transfer", shopB));

        assertThat(transfersB).doesNotContainKey(TRANSFER_EXPECTED);
    }

    // ===========================================================================================
    // 7. ChangeLog Fan-Out & Delta Convergence
    // ===========================================================================================

    @Test
    void stockMovementFansOutUpsertAndDepletionFansOutDelete() {
        transactions.inScope(tillScopeA, () -> {
            ObjectNode payload1 = json.createObjectNode()
                    .put("ownerEntityId", SOCIETY_A.toString())
                    .put("locationId", SHOP_A.toString())
                    .put("stockLotId", LOT_GOOD_1.toString())
                    .put("lotQtyOnHand", "45.000");
            fanOut.onStockMoved(payload1, tillScopeA);
            return null;
        });

        List<Map<String, Object>> changes = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op from kernel.change_log where location_id = ? order by version desc limit 1",
                        SHOP_A);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("table_name")).isEqualTo("lot");
        assertThat(changes.get(0).get("row_id")).isEqualTo(LOT_GOOD_1);
        assertThat(changes.get(0).get("op")).isEqualTo("UPSERT");

        // Deplete lot to zero
        transactions.inScope(tillScopeA, () -> {
            ObjectNode payload2 = json.createObjectNode()
                    .put("ownerEntityId", SOCIETY_A.toString())
                    .put("locationId", SHOP_A.toString())
                    .put("stockLotId", LOT_GOOD_1.toString())
                    .put("lotQtyOnHand", "0.000");
            fanOut.onStockMoved(payload2, tillScopeA);
            return null;
        });

        changes = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op from kernel.change_log where location_id = ? order by version desc limit 1",
                        SHOP_A);
        assertThat(changes.get(0).get("op")).isEqualTo("DELETE");
    }

    @Test
    void nonShopLocationMovementsDoNotFanOutToChangeLog() {
        transactions.inScope(tillScopeA, () -> {
            ObjectNode payload = json.createObjectNode()
                    .put("ownerEntityId", SOCIETY_A.toString())
                    .put("locationId", WAREHOUSE_A.toString())
                    .put("stockLotId", UUID.randomUUID().toString())
                    .put("lotQtyOnHand", "100.000");
            fanOut.onStockMoved(payload, tillScopeA);
            return null;
        });

        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from kernel.change_log where location_id = ?", Integer.class, WAREHOUSE_A);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void transferIssuedAndReceivedFanOutToDestinationShop() {
        UUID transferId = UUID.randomUUID();
        transactions.inScope(tillScopeA, () -> {
            ObjectNode payload = json.createObjectNode()
                    .put("ownerEntityId", SOCIETY_A.toString())
                    .put("toLocationId", SHOP_A.toString())
                    .put("transferId", transferId.toString());
            fanOut.onTransferIssued(payload, tillScopeA);
            return null;
        });

        List<Map<String, Object>> changes = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op from kernel.change_log where location_id = ? and row_id = ?",
                        SHOP_A,
                        transferId);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("table_name")).isEqualTo("expected_transfer");
        assertThat(changes.get(0).get("op")).isEqualTo("UPSERT");

        // Received
        transactions.inScope(tillScopeA, () -> {
            ObjectNode payload = json.createObjectNode()
                    .put("ownerEntityId", SOCIETY_A.toString())
                    .put("toLocationId", SHOP_A.toString())
                    .put("transferId", transferId.toString());
            fanOut.onTransferReceived(payload, tillScopeA);
            return null;
        });

        changes = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op from kernel.change_log where location_id = ? and row_id = ? order by version desc limit 1",
                        SHOP_A,
                        transferId);
        assertThat(changes.get(0).get("op")).isEqualTo("DELETE");
    }

    @Test
    void inventoryConfigChangeFansOutToShops() {
        transactions.inScope(tillScopeA, () -> {
            ObjectNode payload = json.createObjectNode()
                    .put("key", PolicySnapshotContributor.COUNT_TOLERANCE_PCT)
                    .put("scopeEntityId", SOCIETY_A.toString());
            fanOut.onConfigChanged(payload, tillScopeA);
            return null;
        });

        List<Map<String, Object>> changes = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op from kernel.change_log where location_id = ? and table_name = 'inventory_policy' order by version desc limit 1",
                        SHOP_A);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("op")).isEqualTo("UPSERT");
        assertThat(changes.get(0).get("row_id")).isEqualTo(SHOP_A);
    }

    // ===========================================================================================
    // 8. SnapshotBuilder Full Snapshot Integration
    // ===========================================================================================

    @Test
    void snapshotBuilderIncludesM5TablesInFullSnapshot() {
        JsonNode fullSnapshot = json.valueToTree(transactions.inScope(
                tillScopeA, () -> (Object) snapshotBuilder.build(tillScopeA, 0, Duration.ofDays(30))));

        assertThat(fullSnapshot.path("full").asBoolean()).isTrue();
        assertThat(fullSnapshot.path("tables").has("lot")).isTrue();
        assertThat(fullSnapshot.path("tables").has("count_task")).isTrue();
        assertThat(fullSnapshot.path("tables").has("inventory_policy")).isTrue();
        assertThat(fullSnapshot.path("tables").has("expected_transfer")).isTrue();

        JsonNode lotUpserts = fullSnapshot.path("tables").path("lot").path("upserts");
        List<String> lotIds = new ArrayList<>();
        lotUpserts.forEach(r -> lotIds.add(r.path("rowId").asText()));
        assertThat(lotIds).contains(LOT_GOOD_1.toString(), LOT_GOOD_2.toString());

        JsonNode taskUpserts = fullSnapshot.path("tables").path("count_task").path("upserts");
        List<String> taskIds = new ArrayList<>();
        taskUpserts.forEach(r -> taskIds.add(r.path("rowId").asText()));
        assertThat(taskIds).contains(TASK_COUNTING.toString());

        JsonNode policyUpserts =
                fullSnapshot.path("tables").path("inventory_policy").path("upserts");
        List<String> policyIds = new ArrayList<>();
        policyUpserts.forEach(r -> policyIds.add(r.path("rowId").asText()));
        assertThat(policyIds).contains(SHOP_A.toString());

        JsonNode transferUpserts =
                fullSnapshot.path("tables").path("expected_transfer").path("upserts");
        List<String> transferIds = new ArrayList<>();
        transferUpserts.forEach(r -> transferIds.add(r.path("rowId").asText()));
        assertThat(transferIds).contains(TRANSFER_EXPECTED.toString());
    }
}
