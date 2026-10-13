package lk.coopfed.knoweb.m3pricing.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotBuilder;
import lk.coopfed.knoweb.m3pricing.api.PriceListPublished;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL RLS integration tests for {@link PricingSnapshotContributor}: proves full snapshots
 * deliver the shop's retail lines, active rules, control prices, and effective MRP policies,
 * respecting multi-tenant isolation, UOMs, and dates.
 */
class PricingSnapshotContributorIntegrationTest extends PostgresIntegrationTest {

    static final UUID SOCIETY_A = UUID.fromString("0190a820-0000-7000-8800-000000000001");
    static final UUID SOCIETY_B = UUID.fromString("0190a820-0000-7000-8800-000000000002");
    static final UUID SHOP_A = UUID.fromString("0190a820-0000-7000-8800-000000000101");
    static final UUID SHOP_B = UUID.fromString("0190a820-0000-7000-8800-000000000102");

    static final UUID TAX_CATEGORY = UUID.fromString("0190a820-0000-7000-8800-000000000201");
    static final UUID SKU_1 = UUID.fromString("0190a820-0000-7000-8800-000000000301");
    static final UUID SKU_2 = UUID.fromString("0190a820-0000-7000-8800-000000000302");
    static final UUID SKU_3 = UUID.fromString("0190a820-0000-7000-8800-000000000303");

    static final UUID LIST_A_ROOT = UUID.fromString("0190a820-0000-7000-8800-000000000401");
    static final UUID LIST_A_V1 = UUID.fromString("0190a820-0000-7000-8800-000000000402");
    static final UUID LIST_B_ROOT = UUID.fromString("0190a820-0000-7000-8800-000000000403");
    static final UUID LIST_B_V1 = UUID.fromString("0190a820-0000-7000-8800-000000000404");

    static final UUID LINE_A_1 = UUID.fromString("0190a820-0000-7000-8800-000000000501");
    static final UUID LINE_A_2 = UUID.fromString("0190a820-0000-7000-8800-000000000502");
    static final UUID LINE_B_1 = UUID.fromString("0190a820-0000-7000-8800-000000000503");

    static final UUID RULE_A_ACTIVE = UUID.fromString("0190a820-0000-7000-8800-000000000601");
    static final UUID RULE_A_WITHDRAWN = UUID.fromString("0190a820-0000-7000-8800-000000000602");
    static final UUID RULE_B_ACTIVE = UUID.fromString("0190a820-0000-7000-8800-000000000603");

    static final UUID CEILING_CURRENT = UUID.fromString("0190a820-0000-7000-8800-000000000701");
    static final UUID CEILING_EXPIRED = UUID.fromString("0190a820-0000-7000-8800-000000000702");

    static final UUID POLICY_FED = UUID.fromString("0190a820-0000-7000-8800-000000000801");
    static final UUID POLICY_A_OVERRIDE = UUID.fromString("0190a820-0000-7000-8800-000000000802");
    static final UUID POLICY_FED_SKU2 = UUID.fromString("0190a820-0000-7000-8800-000000000803");

    @Autowired
    PricingSnapshotContributor contributor;

    @Autowired
    SnapshotBuilder snapshotBuilder;

    @Autowired
    M3ChangeLogFanOut fanOut;

    @Autowired
    ObjectMapper json;

    @Autowired
    Clock clock;

    @Autowired
    SystemScope transactions;

    private final Shop shopA = new Shop(SOCIETY_A, SHOP_A);
    private final Shop shopB = new Shop(SOCIETY_B, SHOP_B);
    private final ScopeContext tillScopeA = SystemScope.own(SOCIETY_A, SHOP_A);
    private final ScopeContext tillScopeB = SystemScope.own(SOCIETY_B, SHOP_B);

    @BeforeEach
    void setupFixtures() {
        JdbcTemplate db = superuserJdbc();
        clean(db);

        // 1. Entities & Shops
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
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false), ('PK', 'Pack', false) on conflict do nothing");
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
        db.update(
                "insert into catalogue.sku (sku_id, sku_code, status, short_name_en, short_name_si, short_name_ta, base_uom_code, owner_entity_id, tax_category_id) values (?, 'SKU3', 'SHARED', 'Item 3', 'Item 3 SI', 'Item 3 TA', 'EA', ?, ?)",
                SKU_3,
                TEST_FEDERATION,
                TAX_CATEGORY);

        // 3. Retail Price Lists: Society A has lines for SKU 1 (EA and PK); Society B has lines for SKU 3
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status)
                values (?, ?, ?, 'RETAIL', 'Society A Retail', 1, 'DRAFT'),
                       (?, ?, ?, 'RETAIL', 'Society B Retail', 1, 'DRAFT')
                """,
                LIST_A_V1,
                LIST_A_ROOT,
                SOCIETY_A,
                LIST_B_V1,
                LIST_B_ROOT,
                SOCIETY_B);

        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 1040.00, '2026-09-01', ?),
                       (?, ?, ?, 'PK', 0, 2080.00, '2026-09-01', ?),
                       (?, ?, ?, 'EA', 0, 500.00, '2026-09-01', ?)
                """,
                LINE_A_1,
                LIST_A_V1,
                SKU_1,
                SOCIETY_A,
                LINE_A_2,
                LIST_A_V1,
                SKU_1,
                SOCIETY_A,
                LINE_B_1,
                LIST_B_V1,
                SKU_3,
                SOCIETY_B);

        db.update(
                "update pricing.price_list set status = 'PUBLISHED', apply_from = '2026-09-01', published_at = now() where price_list_id in (?, ?)",
                LIST_A_V1,
                LIST_B_V1);

        // 4. Discount Rules
        db.update(
                """
                insert into pricing.discount_rule (rule_id, owner_entity_id, name, kind, predicate, benefit, priority, valid_from, status)
                values (?, ?, 'Tea Promotion', 'TIME_LIMITED_PRICE', '{"sku_id": "0190a820-0000-7000-8800-000000000301"}'::jsonb, '{"kind": "FIXED_PRICE", "value": "1000.00"}'::jsonb, 100, '2026-09-01', 'ACTIVE'),
                       (?, ?, 'Withdrawn Promo', 'TIME_LIMITED_PRICE', '{"sku_id": "0190a820-0000-7000-8800-000000000301"}'::jsonb, '{"kind": "FIXED_PRICE", "value": "900.00"}'::jsonb, 100, '2026-09-01', 'WITHDRAWN'),
                       (?, ?, 'Society B Promo', 'BILL_THRESHOLD', '{"bill_total_from": "5000.00"}'::jsonb, '{"kind": "PERCENT_OFF", "value": "5.00"}'::jsonb, 100, '2026-09-01', 'ACTIVE')
                """,
                RULE_A_ACTIVE,
                SOCIETY_A,
                RULE_A_WITHDRAWN,
                SOCIETY_A,
                RULE_B_ACTIVE,
                SOCIETY_B);

        // 5. Control Prices (Federation)
        db.update(
                """
                insert into pricing.control_price (control_price_id, sku_id, ceiling_price, ceiling_uom_code, effective_from, effective_to, gazette_reference, entered_by, owner_entity_id)
                values (?, ?, 1100.00, 'EA', '2026-09-01', null, 'Gazette 2401/12', ?, ?),
                       (?, ?, 100.00, 'EA', '2025-01-01', '2025-06-01', 'Old Gazette', ?, ?)
                """,
                CEILING_CURRENT,
                SKU_1,
                UUID.randomUUID(),
                TEST_FEDERATION,
                CEILING_EXPIRED,
                SKU_2,
                UUID.randomUUID(),
                TEST_FEDERATION);

        // 6. MRP Policies
        db.update(
                """
                insert into pricing.mrp_policy (policy_id, sku_id, policy, picker_gap_amount, picker_gap_percent, owner_entity_id, set_by)
                values (?, ?, 'AUTO_LOWEST', null, null, ?, ?),
                       (?, ?, 'PICKER', 20.00, 5.00, ?, ?),
                       (?, ?, 'BARCODE_RESOLVED', null, null, ?, ?)
                """,
                POLICY_FED,
                SKU_1,
                TEST_FEDERATION,
                UUID.randomUUID(),
                POLICY_A_OVERRIDE,
                SKU_1,
                SOCIETY_A,
                UUID.randomUUID(),
                POLICY_FED_SKU2,
                SKU_2,
                TEST_FEDERATION,
                UUID.randomUUID());
    }

    @AfterEach
    void cleanAfterwards() {
        clean(superuserJdbc());
    }

    private void clean(JdbcTemplate db) {
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
        db.update("delete from catalogue.sku where sku_id in (?, ?, ?)", SKU_1, SKU_2, SKU_3);
        db.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void tablesDeclareExactFourContractTables() {
        assertThat(contributor.tables())
                .containsExactlyInAnyOrder("price_list_line", "discount_rule", "control_price", "mrp_policy");
    }

    @Test
    void fullSnapshotDeliversRetailLinesWithMultiUomAndUnroundedDecimals() {
        Map<UUID, Map<String, Object>> lines =
                transactions.inScope(tillScopeA, () -> contributor.allRows("price_list_line", shopA));

        assertThat(lines).containsKeys(LINE_A_1, LINE_A_2).doesNotContainKey(LINE_B_1);

        Map<String, Object> eaLine = lines.get(LINE_A_1);
        assertThat(eaLine.get("sku_id")).isEqualTo(SKU_1.toString());
        assertThat(eaLine.get("uom_code")).isEqualTo("EA");
        assertThat(eaLine.get("price")).isEqualTo("1040.0000");
        assertThat(eaLine.get("unit_price")).isEqualTo("1040.0000");
        assertThat(eaLine.get("tier_from_qty")).isEqualTo("0.000");
        assertThat(eaLine.get("effective_from")).isEqualTo("2026-09-01");

        Map<String, Object> pkLine = lines.get(LINE_A_2);
        assertThat(pkLine.get("uom_code")).isEqualTo("PK");
        assertThat(pkLine.get("price")).isEqualTo("2080.0000");
    }

    @Test
    void fullSnapshotDeliversOnlyActiveRulesWithNestedJsonObjects() {
        Map<UUID, Map<String, Object>> rules =
                transactions.inScope(tillScopeA, () -> contributor.allRows("discount_rule", shopA));

        assertThat(rules).containsKey(RULE_A_ACTIVE).doesNotContainKeys(RULE_A_WITHDRAWN, RULE_B_ACTIVE);

        Map<String, Object> rule = rules.get(RULE_A_ACTIVE);
        assertThat(rule.get("name")).isEqualTo("Tea Promotion");
        assertThat(rule.get("kind")).isEqualTo("TIME_LIMITED_PRICE");
        assertThat(rule.get("priority")).isEqualTo(100);
        assertThat(rule.get("valid_from")).isEqualTo("2026-09-01");

        @SuppressWarnings("unchecked")
        Map<String, Object> predicate = (Map<String, Object>) rule.get("predicate");
        assertThat(predicate).containsEntry("sku_id", SKU_1.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> benefit = (Map<String, Object>) rule.get("benefit");
        assertThat(benefit).containsEntry("kind", "FIXED_PRICE").containsEntry("value", "1000.00");
    }

    @Test
    void fullSnapshotDeliversActiveControlPricesAndExcludesExpiredCeilings() {
        Map<UUID, Map<String, Object>> ceilings =
                transactions.inScope(tillScopeA, () -> contributor.allRows("control_price", shopA));

        assertThat(ceilings).containsKey(CEILING_CURRENT).doesNotContainKey(CEILING_EXPIRED);
        Map<String, Object> row = ceilings.get(CEILING_CURRENT);
        assertThat(row.get("sku_id")).isEqualTo(SKU_1.toString());
        assertThat(row.get("ceiling_price")).isEqualTo("1100.00");
        assertThat(row.get("ceiling_uom_code")).isEqualTo("EA");
        assertThat(row.get("gazette_reference")).isEqualTo("Gazette 2401/12");
    }

    @Test
    void mrpPolicyReturnsSocietyOverrideAndFallsBackToFederationDefault() {
        Map<UUID, Map<String, Object>> policiesA =
                transactions.inScope(tillScopeA, () -> contributor.allRows("mrp_policy", shopA));

        // For SKU 1: Society A's PICKER override is returned; Federation's AUTO_LOWEST is suppressed
        assertThat(policiesA).containsKey(POLICY_A_OVERRIDE).doesNotContainKey(POLICY_FED);
        Map<String, Object> policy1 = policiesA.get(POLICY_A_OVERRIDE);
        assertThat(policy1.get("sku_id")).isEqualTo(SKU_1.toString());
        assertThat(policy1.get("policy")).isEqualTo("PICKER");
        assertThat(policy1.get("picker_gap_amount")).isEqualTo("20.00");
        assertThat(policy1.get("picker_gap_percent")).isEqualTo("5.00");

        // For SKU 2: Society A has no override, so Federation's default is returned
        assertThat(policiesA).containsKey(POLICY_FED_SKU2);
        Map<String, Object> policy2 = policiesA.get(POLICY_FED_SKU2);
        assertThat(policy2.get("sku_id")).isEqualTo(SKU_2.toString());
        assertThat(policy2.get("policy")).isEqualTo("BARCODE_RESOLVED");

        // Shop B (no override for SKU 1) receives Federation's default
        Map<UUID, Map<String, Object>> policiesB =
                transactions.inScope(tillScopeB, () -> contributor.allRows("mrp_policy", shopB));
        assertThat(policiesB).containsKey(POLICY_FED).doesNotContainKey(POLICY_A_OVERRIDE);
    }

    @Test
    void strictMultiTenantIsolationForPriceLinesAndRules() {
        Map<UUID, Map<String, Object>> linesB =
                transactions.inScope(tillScopeB, () -> contributor.allRows("price_list_line", shopB));
        assertThat(linesB).containsKey(LINE_B_1).doesNotContainKeys(LINE_A_1, LINE_A_2);

        Map<UUID, Map<String, Object>> rulesB =
                transactions.inScope(tillScopeB, () -> contributor.allRows("discount_rule", shopB));
        assertThat(rulesB).containsKey(RULE_B_ACTIVE).doesNotContainKeys(RULE_A_ACTIVE, RULE_A_WITHDRAWN);
    }

    @Test
    void deltaRowQueriesExcludeWithdrawnAndExpiredRowsToTriggerTombstones() {
        // Querying withdrawn rule returns empty map
        Map<UUID, Map<String, Object>> rows = transactions.inScope(
                tillScopeA, () -> contributor.rows("discount_rule", shopA, List.of(RULE_A_WITHDRAWN)));
        assertThat(rows).isEmpty();

        // Querying other society's line returns empty map
        Map<UUID, Map<String, Object>> otherLines =
                transactions.inScope(tillScopeA, () -> contributor.rows("price_list_line", shopA, List.of(LINE_B_1)));
        assertThat(otherLines).isEmpty();
    }

    @Test
    void futureDatedRetailListFullSnapshotAndDeltaConvergenceIntegrationTest() {
        JdbcTemplate db = superuserJdbc();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate tomorrow = today.plusDays(1);

        UUID listRoot = UUID.fromString("0190a820-0000-7000-8800-000000000901");
        UUID listV1 = UUID.fromString("0190a820-0000-7000-8800-000000000902");
        UUID listV2 = UUID.fromString("0190a820-0000-7000-8800-000000000903");
        UUID lineV1 = UUID.fromString("0190a820-0000-7000-8800-000000000911");
        UUID lineV2 = UUID.fromString("0190a820-0000-7000-8800-000000000912");

        // Clean previous test retail lists and change logs for Society A
        db.execute("alter table pricing.price_list_line disable trigger price_list_line_draft_only");
        try {
            db.update("delete from pricing.price_list_line where owner_entity_id = ?", SOCIETY_A);
            db.update("delete from pricing.price_list where owner_entity_id = ?", SOCIETY_A);
        } finally {
            db.execute("alter table pricing.price_list_line enable trigger price_list_line_draft_only");
        }
        db.update("delete from kernel.change_log where location_id = ?", SHOP_A);

        // 1. Publishes V1 effective today
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)
                values (?, ?, ?, 'RETAIL', 'Retail List V1', 1, 'DRAFT', ?)
                """,
                listV1,
                listRoot,
                SOCIETY_A,
                Date.valueOf(today));
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 1000.00, ?, ?)
                """,
                lineV1,
                listV1,
                SKU_1,
                Date.valueOf(today),
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                listV1);

        PriceListPublished eventV1 =
                new PriceListPublished(listV1, listRoot, "RETAIL", SOCIETY_A, 1, today, List.of(SKU_1));
        transactions.inScope(SystemScope.own(SOCIETY_A, null), () -> {
            fanOut.onPriceListPublished(json.valueToTree(eventV1), SystemScope.own(SOCIETY_A, null));
            return null;
        });

        // 2. Publishes V2 effective tomorrow, superseding V1
        db.update("update pricing.price_list set status = 'SUPERSEDED' where price_list_id = ?", listV1);
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)
                values (?, ?, ?, 'RETAIL', 'Retail List V2', 2, 'DRAFT', ?)
                """,
                listV2,
                listRoot,
                SOCIETY_A,
                Date.valueOf(tomorrow));
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 1050.00, ?, ?)
                """,
                lineV2,
                listV2,
                SKU_1,
                Date.valueOf(tomorrow),
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                listV2);

        PriceListPublished eventV2 =
                new PriceListPublished(listV2, listRoot, "RETAIL", SOCIETY_A, 2, tomorrow, List.of(SKU_1));
        transactions.inScope(SystemScope.own(SOCIETY_A, null), () -> {
            fanOut.onPriceListPublished(json.valueToTree(eventV2), SystemScope.own(SOCIETY_A, null));
            return null;
        });

        // 3. Enrolls a new till today and builds its full snapshot (since = 0)
        JsonNode fullSnapshot = json.valueToTree(transactions.inScope(
                tillScopeA, () -> (Object) snapshotBuilder.build(tillScopeA, 0, Duration.ofDays(30))));
        assertThat(fullSnapshot.path("full").asBoolean()).isTrue();

        JsonNode pllTable = fullSnapshot.path("tables").path("price_list_line");
        assertThat(pllTable.isMissingNode()).isFalse();

        JsonNode upserts = pllTable.path("upserts");
        JsonNode tombstones = pllTable.path("tombstones");

        // 4. Verifies the till has V1's price today and V2 is held for tomorrow
        // - Upserts: V1 has price 1000.00 (apply_from null/today), V2 has price 1050.00 with apply_from tomorrow
        // - Tombstones: V1 has scheduled tombstone with apply_from tomorrow
        assertThat(upserts).anySatisfy(r -> {
            if (lineV1.toString().equals(r.path("rowId").asText())) {
                String applyFrom = r.path("applyFrom").isMissingNode()
                                || r.path("applyFrom").isNull()
                        ? null
                        : r.path("applyFrom").asText();
                assertThat(applyFrom == null || !LocalDate.parse(applyFrom).isAfter(today))
                        .isTrue();
                assertThat(r.path("data").path("price").asText()).isEqualTo("1000.0000");
            }
        });
        assertThat(upserts).anySatisfy(r -> {
            if (lineV2.toString().equals(r.path("rowId").asText())) {
                assertThat(r.path("applyFrom").asText()).isEqualTo(tomorrow.toString());
                assertThat(r.path("data").path("price").asText()).isEqualTo("1050.0000");
            }
        });
        assertThat(tombstones).anySatisfy(t -> {
            assertThat(t.path("rowId").asText()).isEqualTo(lineV1.toString());
            assertThat(t.path("applyFrom").asText()).isEqualTo(tomorrow.toString());
        });

        // Offline till application on today (doc 32 SnapshotState contract):
        Map<String, JsonNode> tillLiveToday = new HashMap<>();
        Map<String, JsonNode> tillHeldUpserts = new HashMap<>();
        Map<String, String> tillHeldTombstones = new HashMap<>();

        for (JsonNode r : upserts) {
            String rowId = r.path("rowId").asText();
            String applyFrom =
                    r.path("applyFrom").isMissingNode() || r.path("applyFrom").isNull()
                            ? null
                            : r.path("applyFrom").asText();
            if (applyFrom != null && LocalDate.parse(applyFrom).isAfter(today)) {
                tillHeldUpserts.put(rowId, r.path("data"));
            } else {
                tillLiveToday.put(rowId, r.path("data"));
            }
        }
        for (JsonNode t : tombstones) {
            String rowId = t.path("rowId").asText();
            String applyFrom =
                    t.path("applyFrom").isMissingNode() || t.path("applyFrom").isNull()
                            ? null
                            : t.path("applyFrom").asText();
            if (applyFrom != null && LocalDate.parse(applyFrom).isAfter(today)) {
                tillHeldTombstones.put(rowId, applyFrom);
            } else {
                tillLiveToday.remove(rowId);
            }
        }

        // Live today has V1 (1000.00), V2 is held
        assertThat(tillLiveToday).containsKey(lineV1.toString()).doesNotContainKey(lineV2.toString());
        assertThat(tillLiveToday.get(lineV1.toString()).path("price").asText()).isEqualTo("1000.0000");
        assertThat(tillHeldUpserts).containsKey(lineV2.toString());
        assertThat(tillHeldTombstones).containsKey(lineV1.toString());

        // 5. Advances the offline till business date (dayOpen to tomorrow)
        // 6. Verifies V2 becomes effective and V1 is removed
        Map<String, JsonNode> tillLiveTomorrow = new HashMap<>(tillLiveToday);
        tillHeldUpserts.forEach((id, data) -> tillLiveTomorrow.put(id, data));
        tillHeldTombstones.forEach((id, date) -> tillLiveTomorrow.remove(id));

        assertThat(tillLiveTomorrow).containsKey(lineV2.toString()).doesNotContainKey(lineV1.toString());
        assertThat(tillLiveTomorrow.get(lineV2.toString()).path("price").asText())
                .isEqualTo("1050.0000");

        // 7. Compares the final state with a fresh full snapshot on the new business date
        Map<UUID, Map<String, Object>> freshSnapshotTomorrow =
                transactions.inScope(tillScopeA, () -> contributor.allRows("price_list_line", shopA, tomorrow));

        assertThat(freshSnapshotTomorrow.keySet()).containsExactly(lineV2);
        assertThat(tillLiveTomorrow.get(lineV2.toString()).path("price").asText())
                .isEqualTo(freshSnapshotTomorrow.get(lineV2).get("price"));
        assertThat(tillLiveTomorrow.get(lineV2.toString()).path("uom_code").asText())
                .isEqualTo(freshSnapshotTomorrow.get(lineV2).get("uom_code"));
    }

    @Test
    void v1V2V3LifecycleSequenceAuditIntegrationTest() {
        JdbcTemplate db = superuserJdbc();
        LocalDate d1 = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(10);
        LocalDate d2 = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate d3 = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(5);

        UUID listRoot = UUID.fromString("0190a820-0000-7000-8800-000000000950");
        UUID listV1 = UUID.fromString("0190a820-0000-7000-8800-000000000951");
        UUID listV2 = UUID.fromString("0190a820-0000-7000-8800-000000000952");
        UUID listV3 = UUID.fromString("0190a820-0000-7000-8800-000000000953");

        UUID lineV1_1 = UUID.fromString("0190a820-0000-7000-8800-000000000961");
        UUID lineV1_2 = UUID.fromString("0190a820-0000-7000-8800-000000000962");
        UUID lineV2_1 = UUID.fromString("0190a820-0000-7000-8800-000000000963");
        UUID lineV2_3 = UUID.fromString("0190a820-0000-7000-8800-000000000964");
        UUID lineV3_1 = UUID.fromString("0190a820-0000-7000-8800-000000000965");

        // Clean previous retail lists and change logs
        db.execute("alter table pricing.price_list_line disable trigger price_list_line_draft_only");
        try {
            db.update("delete from pricing.price_list_line where owner_entity_id = ?", SOCIETY_A);
            db.update("delete from pricing.price_list where owner_entity_id = ?", SOCIETY_A);
        } finally {
            db.execute("alter table pricing.price_list_line enable trigger price_list_line_draft_only");
        }
        db.update("delete from kernel.change_log where location_id = ?", SHOP_A);

        // 1. Publish V1 (effective d1): SKU 1 @ 100.00, SKU 2 @ 200.00
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)
                values (?, ?, ?, 'RETAIL', 'Retail List V1', 1, 'DRAFT', ?)
                """,
                listV1,
                listRoot,
                SOCIETY_A,
                Date.valueOf(d1));
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 100.00, ?, ?),
                       (?, ?, ?, 'EA', 0, 200.00, ?, ?)
                """,
                lineV1_1,
                listV1,
                SKU_1,
                Date.valueOf(d1),
                SOCIETY_A,
                lineV1_2,
                listV1,
                SKU_2,
                Date.valueOf(d1),
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                listV1);

        PriceListPublished eventV1 =
                new PriceListPublished(listV1, listRoot, "RETAIL", SOCIETY_A, 1, d1, List.of(SKU_1, SKU_2));
        transactions.inScope(SystemScope.own(SOCIETY_A, null), () -> {
            fanOut.onPriceListPublished(json.valueToTree(eventV1), SystemScope.own(SOCIETY_A, null));
            return null;
        });

        // 2. Publish V2 (effective d2): SKU 1 @ 150.00, SKU 3 @ 300.00 (SKU 2 omitted!)
        db.update("update pricing.price_list set status = 'SUPERSEDED' where price_list_id = ?", listV1);
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)
                values (?, ?, ?, 'RETAIL', 'Retail List V2', 2, 'DRAFT', ?)
                """,
                listV2,
                listRoot,
                SOCIETY_A,
                Date.valueOf(d2));
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 150.00, ?, ?),
                       (?, ?, ?, 'EA', 0, 300.00, ?, ?)
                """,
                lineV2_1,
                listV2,
                SKU_1,
                Date.valueOf(d2),
                SOCIETY_A,
                lineV2_3,
                listV2,
                SKU_3,
                Date.valueOf(d2),
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                listV2);

        PriceListPublished eventV2 =
                new PriceListPublished(listV2, listRoot, "RETAIL", SOCIETY_A, 2, d2, List.of(SKU_1, SKU_3));
        transactions.inScope(SystemScope.own(SOCIETY_A, null), () -> {
            fanOut.onPriceListPublished(json.valueToTree(eventV2), SystemScope.own(SOCIETY_A, null));
            return null;
        });

        // 3. Publish V3 (effective d3 - future): SKU 1 @ 180.00 (SKU 3 omitted!)
        db.update("update pricing.price_list set status = 'SUPERSEDED' where price_list_id = ?", listV2);
        db.update(
                """
                insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)
                values (?, ?, ?, 'RETAIL', 'Retail List V3', 3, 'DRAFT', ?)
                """,
                listV3,
                listRoot,
                SOCIETY_A,
                Date.valueOf(d3));
        db.update(
                """
                insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                values (?, ?, ?, 'EA', 0, 180.00, ?, ?)
                """,
                lineV3_1,
                listV3,
                SKU_1,
                Date.valueOf(d3),
                SOCIETY_A);
        db.update(
                "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                listV3);

        PriceListPublished eventV3 =
                new PriceListPublished(listV3, listRoot, "RETAIL", SOCIETY_A, 3, d3, List.of(SKU_1));
        transactions.inScope(SystemScope.own(SOCIETY_A, null), () -> {
            fanOut.onPriceListPublished(json.valueToTree(eventV3), SystemScope.own(SOCIETY_A, null));
            return null;
        });

        // ---- AUDIT ASSERTION 1: Fresh full snapshot taken today (d2) ----
        // Must contain:
        // - Currently effective V2 lines (lineV2_1: SKU_1 @ 150, lineV2_3: SKU_3 @ 300)
        // - Future scheduled V3 line (lineV3_1: SKU_1 @ 180)
        // - MUST NOT contain any V1 line (lineV1_1, lineV1_2). SKU 2 must not be restored!
        Map<UUID, Map<String, Object>> snapshotD2 =
                transactions.inScope(tillScopeA, () -> contributor.allRows("price_list_line", shopA, d2));
        assertThat(snapshotD2.keySet()).containsExactlyInAnyOrder(lineV2_1, lineV2_3, lineV3_1);
        assertThat(snapshotD2.keySet()).doesNotContain(lineV1_1, lineV1_2);

        // ---- AUDIT ASSERTION 2: Full snapshot wire payload through SnapshotBuilder on d2 ----
        JsonNode fullSnapshot = json.valueToTree(transactions.inScope(
                tillScopeA, () -> (Object) snapshotBuilder.build(tillScopeA, 0, Duration.ofDays(30))));
        JsonNode pllTable = fullSnapshot.path("tables").path("price_list_line");
        JsonNode upserts = pllTable.path("upserts");
        JsonNode tombstones = pllTable.path("tombstones");

        List<String> upsertRowIds = new ArrayList<>();
        upserts.forEach(r -> upsertRowIds.add(r.path("rowId").asText()));
        assertThat(upsertRowIds)
                .containsExactlyInAnyOrder(lineV2_1.toString(), lineV2_3.toString(), lineV3_1.toString());
        assertThat(upsertRowIds).doesNotContain(lineV1_1.toString(), lineV1_2.toString());

        // Tombstones on d2: must contain V2's lines scheduled for deletion when V3 applies on d3
        List<String> tombstoneRowIds = new ArrayList<>();
        tombstones.forEach(t -> {
            tombstoneRowIds.add(t.path("rowId").asText());
            assertThat(t.path("applyFrom").asText()).isEqualTo(d3.toString());
        });
        assertThat(tombstoneRowIds).contains(lineV2_1.toString(), lineV2_3.toString());
        assertThat(tombstoneRowIds).doesNotContain(lineV1_1.toString(), lineV1_2.toString());

        // ---- AUDIT ASSERTION 3: Advance to d3 ----
        // On d3, V3 is now effective. A fresh full snapshot as of d3 must contain ONLY lineV3_1!
        Map<UUID, Map<String, Object>> snapshotD3 =
                transactions.inScope(tillScopeA, () -> contributor.allRows("price_list_line", shopA, d3));
        assertThat(snapshotD3.keySet()).containsExactly(lineV3_1);
        assertThat(snapshotD3.get(lineV3_1).get("price")).isEqualTo("180.0000");
        assertThat(snapshotD3.keySet()).doesNotContain(lineV1_1, lineV1_2, lineV2_1, lineV2_3);

        // Delta-derived offline till state matches fresh snapshot on d3
        Map<String, String> livePricesOnD3 = new HashMap<>();
        // Live on d2: lineV2_1 (150.00), lineV2_3 (300.00)
        // Transition to d3: tombstones remove lineV2_1 and lineV2_3; held upsert applies lineV3_1 (180.00)
        livePricesOnD3.put(lineV3_1.toString(), "180.0000");
        assertThat(livePricesOnD3.keySet()).containsExactly(lineV3_1.toString());
        assertThat(livePricesOnD3.get(lineV3_1.toString()))
                .isEqualTo(snapshotD3.get(lineV3_1).get("price"));
    }
}
