package lk.coopfed.knoweb.m3pricing.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotBuilder;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceRescinded;
import lk.coopfed.knoweb.m3pricing.api.MrpPolicyChanged;
import lk.coopfed.knoweb.m3pricing.api.PriceListPublished;
import lk.coopfed.knoweb.m3pricing.api.RuleActivated;
import lk.coopfed.knoweb.m3pricing.api.RuleWithdrawn;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL integration tests for {@link M3ChangeLogFanOut}: proves that central retail price
 * publications, discount rule mutations, control price entries, and MRP policy overrides fan out
 * correctly to shop change logs and reach enrolled tills in subsequent deltas.
 */
class M3ChangeLogFanOutIntegrationTest extends PostgresIntegrationTest {

    static final UUID SOCIETY_A = UUID.fromString("0190a820-0000-7000-8800-000000000001");
    static final UUID SOCIETY_B = UUID.fromString("0190a820-0000-7000-8800-000000000002");
    static final UUID SHOP_A = UUID.fromString("0190a820-0000-7000-8800-000000000101");
    static final UUID SHOP_B = UUID.fromString("0190a820-0000-7000-8800-000000000102");

    static final UUID TAX_CATEGORY = UUID.fromString("0190a820-0000-7000-8800-000000000201");
    static final UUID SKU_1 = UUID.fromString("0190a820-0000-7000-8800-000000000301");
    static final UUID SKU_2 = UUID.fromString("0190a820-0000-7000-8800-000000000302");

    static final UUID LIST_ROOT = UUID.fromString("0190a820-0000-7000-8800-000000000401");
    static final UUID LIST_V1 = UUID.fromString("0190a820-0000-7000-8800-000000000402");
    static final UUID LIST_V2 = UUID.fromString("0190a820-0000-7000-8800-000000000403");

    static final UUID LINE_V1_1 = UUID.fromString("0190a820-0000-7000-8800-000000000501");
    static final UUID LINE_V2_1 = UUID.fromString("0190a820-0000-7000-8800-000000000502");

    static final UUID RULE_1 = UUID.fromString("0190a820-0000-7000-8800-000000000601");
    static final UUID CONTROL_1 = UUID.fromString("0190a820-0000-7000-8800-000000000701");
    static final UUID CONTROL_CLOSED = UUID.fromString("0190a820-0000-7000-8800-000000000702");
    static final UUID POLICY_1 = UUID.fromString("0190a820-0000-7000-8800-000000000801");
    static final UUID POLICY_FED = UUID.fromString("0190a820-0000-7000-8800-000000000802");

    @Autowired
    M3ChangeLogFanOut fanOut;

    @Autowired
    SnapshotBuilder snapshotBuilder;

    @Autowired
    SystemScope transactions;

    @Autowired
    ObjectMapper json;

    private final ScopeContext centralScope = SystemScope.own(TEST_FEDERATION, null);
    private final ScopeContext societyScopeA = SystemScope.own(SOCIETY_A, null);
    private final ScopeContext tillScopeA = SystemScope.own(SOCIETY_A, SHOP_A);
    private final ScopeContext tillScopeB = SystemScope.own(SOCIETY_B, SHOP_B);

    @BeforeEach
    void setupFixtures() {
        JdbcTemplate db = superuserJdbc();
        clean(db);

        // 1. Entities & Active Shops
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'MPCS-A', 'Society A', 'ACTIVE')",
                SOCIETY_A);
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'MPCS-B', 'Society B', 'ACTIVE')",
                SOCIETY_B);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status) values (?, ?, 'S-A', 'SHOP', 'Shop A', 'en', 'ACTIVE')",
                SHOP_A,
                SOCIETY_A);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status) values (?, ?, 'S-B', 'SHOP', 'Shop B', 'en', 'ACTIVE')",
                SHOP_B,
                SOCIETY_B);

        // 2. SKUs & Tax Category
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
    }

    @AfterEach
    void cleanAfterwards() {
        clean(superuserJdbc());
    }

    private void clean(JdbcTemplate db) {
        db.update("delete from kernel.change_log where location_id in (?, ?)", SHOP_A, SHOP_B);
        db.execute("alter table pricing.price_list_line disable trigger price_list_line_draft_only");
        try {
            db.update("delete from pricing.price_list_line where owner_entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
            db.update("delete from pricing.price_list where owner_entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        } finally {
            db.execute("alter table pricing.price_list_line enable trigger price_list_line_draft_only");
        }
        db.update("delete from pricing.discount_rule where owner_entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        db.update("delete from pricing.control_price where owner_entity_id = ?", TEST_FEDERATION);
        db.update(
                "delete from pricing.mrp_policy where owner_entity_id in (?, ?, ?)",
                SOCIETY_A,
                SOCIETY_B,
                TEST_FEDERATION);
        db.update("delete from party.location where location_id in (?, ?)", SHOP_A, SHOP_B);
        db.update("delete from party.entity_party_directory where entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        db.update("delete from party.entity where entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        db.update(
                "delete from catalogue.location_assortment where owner_entity_id in (?, ?, ?)",
                SOCIETY_A,
                SOCIETY_B,
                TEST_FEDERATION);
        db.update("delete from catalogue.location_assortment where sku_id in (?, ?)", SKU_1, SKU_2);
        db.update("delete from catalogue.sku where sku_id in (?, ?)", SKU_1, SKU_2);
        db.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void retailPriceListPublicationFansOutAndTombstonesOldVersionLines() {
        JdbcTemplate db = superuserJdbc();
        // Setup Version 1 (DRAFT -> SUPERSEDED)
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status)
                values (?, ?, ?, 'RETAIL', 'Shelf List v1', 1, 'DRAFT')
                """,
                LIST_V1,
                LIST_ROOT,
                SOCIETY_A);
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 1000.00, '2026-09-01', ?)
                """,
                LINE_V1_1,
                LIST_V1,
                SKU_1,
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'SUPERSEDED', apply_from = '2026-09-01', published_at = now() where price_list_id = ?",
                LIST_V1);

        // Setup Version 2 (DRAFT -> PUBLISHED)
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status)
                values (?, ?, ?, 'RETAIL', 'Shelf List v2', 2, 'DRAFT')
                """,
                LIST_V2,
                LIST_ROOT,
                SOCIETY_A);
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 1050.00, '2026-10-15', ?)
                """,
                LINE_V2_1,
                LIST_V2,
                SKU_1,
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'PUBLISHED', apply_from = '2026-10-15', published_at = now() where price_list_id = ?",
                LIST_V2);

        PriceListPublished event = new PriceListPublished(
                LIST_V2, LIST_ROOT, "RETAIL", SOCIETY_A, 2, LocalDate.of(2026, 10, 15), List.of(SKU_1));

        transactions.inScope(societyScopeA, () -> {
            fanOut.onPriceListPublished(json.valueToTree(event), societyScopeA);
            return null;
        });

        // Verify change_log for Shop A: has upsert for LINE_V2_1 and delete for LINE_V1_1
        List<Map<String, Object>> changesA = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op, apply_from, urgent from kernel.change_log where location_id = ? order by row_id",
                        SHOP_A);
        assertThat(changesA).hasSize(2);
        assertThat(changesA).anySatisfy(c -> {
            assertThat(c.get("table_name")).isEqualTo("price_list_line");
            assertThat(c.get("row_id")).isEqualTo(LINE_V2_1);
            assertThat(c.get("op")).isEqualTo("UPSERT");
            assertThat(c.get("apply_from").toString()).isEqualTo("2026-10-15");
            assertThat(c.get("urgent")).isEqualTo(false);
        });
        assertThat(changesA).anySatisfy(c -> {
            assertThat(c.get("table_name")).isEqualTo("price_list_line");
            assertThat(c.get("row_id")).isEqualTo(LINE_V1_1);
            assertThat(c.get("op")).isEqualTo("DELETE");
            assertThat(c.get("apply_from").toString()).isEqualTo("2026-10-15");
        });

        // Shop B of Society B receives zero changes
        Integer countB = superuserJdbc()
                .queryForObject("select count(*) from kernel.change_log where location_id = ?", Integer.class, SHOP_B);
        assertThat(countB).isEqualTo(0);
    }

    @Test
    void nonRetailPriceListsDoNotFanOut() {
        PriceListPublished event = new PriceListPublished(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TRADE",
                SOCIETY_A,
                1,
                LocalDate.of(2026, 10, 15),
                List.of(SKU_1));

        transactions.inScope(societyScopeA, () -> {
            fanOut.onPriceListPublished(json.valueToTree(event), societyScopeA);
            return null;
        });

        Integer count = superuserJdbc()
                .queryForObject("select count(*) from kernel.change_log where location_id = ?", Integer.class, SHOP_A);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void ruleActivationAndWithdrawalProduceUpsertAndUrgentDelete() {
        RuleActivated activated = new RuleActivated(
                RULE_1, SOCIETY_A, "TIME_LIMITED_PRICE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        transactions.inScope(societyScopeA, () -> {
            fanOut.onRuleActivated(json.valueToTree(activated), societyScopeA);
            return null;
        });

        Map<String, Object> actLog = superuserJdbc()
                .queryForMap(
                        "select table_name, row_id, op, apply_from, urgent from kernel.change_log where location_id = ? and row_id = ?",
                        SHOP_A,
                        RULE_1);
        assertThat(actLog.get("table_name")).isEqualTo("discount_rule");
        assertThat(actLog.get("op")).isEqualTo("UPSERT");
        assertThat(actLog.get("apply_from").toString()).isEqualTo("2026-10-01");
        assertThat(actLog.get("urgent")).isEqualTo(false);

        // Withdraw rule
        RuleWithdrawn withdrawn = new RuleWithdrawn(RULE_1, SOCIETY_A, "TIME_LIMITED_PRICE");
        transactions.inScope(societyScopeA, () -> {
            fanOut.onRuleWithdrawn(json.valueToTree(withdrawn), societyScopeA);
            return null;
        });

        List<Map<String, Object>> logs = superuserJdbc()
                .queryForList(
                        "select table_name, row_id, op, urgent from kernel.change_log where location_id = ? and row_id = ? order by version desc",
                        SHOP_A,
                        RULE_1);
        assertThat(logs.get(0).get("op")).isEqualTo("DELETE");
        assertThat(logs.get(0).get("urgent")).isEqualTo(true);
    }

    @Test
    void controlPriceEntryAndRescissionFanOutUrgentlyToAllShops() {
        ControlPriceEntered entered = new ControlPriceEntered(
                CONTROL_1,
                SKU_1,
                new BigDecimal("1100.00"),
                "EA",
                LocalDate.of(2026, 10, 1),
                null,
                "Gazette 2401/12",
                CONTROL_CLOSED);

        transactions.inScope(centralScope, () -> {
            fanOut.onControlPriceEntered(json.valueToTree(entered), centralScope);
            return null;
        });

        // Both Shop A and Shop B receive urgent entries
        for (UUID shopId : List.of(SHOP_A, SHOP_B)) {
            List<Map<String, Object>> entries = superuserJdbc()
                    .queryForList(
                            "select row_id, op, urgent from kernel.change_log where location_id = ? and table_name = 'control_price'",
                            shopId);
            assertThat(entries).hasSize(2);
            assertThat(entries).allSatisfy(e -> assertThat(e.get("urgent")).isEqualTo(true));
        }

        // Rescind
        ControlPriceRescinded rescinded =
                new ControlPriceRescinded(CONTROL_1, SKU_1, LocalDate.of(2026, 10, 20), "Rescission Gazette");

        transactions.inScope(centralScope, () -> {
            fanOut.onControlPriceRescinded(json.valueToTree(rescinded), centralScope);
            return null;
        });

        List<Map<String, Object>> latest = superuserJdbc()
                .queryForList(
                        "select op, urgent from kernel.change_log where location_id = ? and row_id = ? order by version desc",
                        SHOP_A,
                        CONTROL_1);
        assertThat(latest.get(0).get("op")).isEqualTo("UPSERT");
        assertThat(latest.get(0).get("urgent")).isEqualTo(true);
    }

    @Test
    void mpcsMrpPolicyOverrideFansOutAndDeletesFederationDefault() {
        JdbcTemplate db = superuserJdbc();
        // Setup Federation default policy in DB
        db.update(
                "insert into pricing.mrp_policy (policy_id, sku_id, policy, owner_entity_id, set_by) values (?, ?, 'AUTO_LOWEST', ?, ?)",
                POLICY_FED,
                SKU_1,
                TEST_FEDERATION,
                UUID.randomUUID());

        // Society A sets override
        MrpPolicyChanged event = new MrpPolicyChanged(
                POLICY_1, SOCIETY_A, SKU_1, "PICKER", new BigDecimal("20.00"), new BigDecimal("5.00"));

        transactions.inScope(societyScopeA, () -> {
            fanOut.onMrpPolicyChanged(json.valueToTree(event), societyScopeA);
            return null;
        });

        List<Map<String, Object>> logs = superuserJdbc()
                .queryForList(
                        "select row_id, op from kernel.change_log where location_id = ? and table_name = 'mrp_policy' order by row_id",
                        SHOP_A);
        assertThat(logs).hasSize(2);
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.get("row_id")).isEqualTo(POLICY_1);
            assertThat(l.get("op")).isEqualTo("UPSERT");
        });
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.get("row_id")).isEqualTo(POLICY_FED);
            assertThat(l.get("op")).isEqualTo("DELETE");
        });

        // Shop B of Society B receives zero changes from Society A's override
        Integer countB = superuserJdbc()
                .queryForObject("select count(*) from kernel.change_log where location_id = ?", Integer.class, SHOP_B);
        assertThat(countB).isEqualTo(0);
    }

    @Test
    void primaryAcceptanceCriterionCentralPricePublicationReachesTillDelta() {
        JdbcTemplate db = superuserJdbc();
        // Enrolled till currently holds snapshot version 10
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status)
                values (?, ?, ?, 'RETAIL', 'Retail List', 1, 'DRAFT')
                """,
                LIST_V1,
                LIST_ROOT,
                SOCIETY_A);

        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 1040.00, '2026-10-01', ?)
                """,
                LINE_V1_1,
                LIST_V1,
                SKU_1,
                SOCIETY_A);

        db.update(
                "update pricing.price_list set status = 'PUBLISHED', apply_from = '2026-10-01', published_at = now() where price_list_id = ?",
                LIST_V1);

        // Central publishes retail list
        PriceListPublished event = new PriceListPublished(
                LIST_V1, LIST_ROOT, "RETAIL", SOCIETY_A, 1, LocalDate.of(2026, 10, 01), List.of(SKU_1));

        transactions.inScope(societyScopeA, () -> {
            fanOut.onPriceListPublished(json.valueToTree(event), societyScopeA);
            return null;
        });

        // Request delta for Shop A since version 0
        com.fasterxml.jackson.databind.JsonNode delta = json.valueToTree(transactions.inScope(
                tillScopeA, () -> (Object) snapshotBuilder.build(tillScopeA, 0, Duration.ofDays(30))));

        assertThat(delta.path("full").asBoolean()).isTrue();
        assertThat(delta.path("tables").has("price_list_line")).isTrue();
        assertThat(delta.path("tables").path("price_list_line").path("upserts").size())
                .isGreaterThan(0);
        assertThat(delta.path("signature").asText()).isNotBlank();
        assertThat(delta.path("manifest").asText()).contains("price_list_line");
    }
}
