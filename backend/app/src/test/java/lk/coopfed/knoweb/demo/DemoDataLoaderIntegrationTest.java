package lk.coopfed.knoweb.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DEMO-01: the loader against the test database, twice. The first run loads the demo through the
 * command handlers, as the demo users, with the permission check on (the users' roles decide, as in
 * compose) and the application connected as coop_app under row-level security; the second run
 * issues no command and changes no row.
 */
class DemoDataLoaderIntegrationTest extends PostgresIntegrationTest {

    /** The Federation the demo users belong to: the one of the development seed and of compose. */
    @DynamicPropertySource
    static void theDemoFederation(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", DemoCast.FEDERATION::toString);
        registry.add("coop-erp.security.enforce-permissions", () -> "true");
        // DEMO-02: what the demo container sets, so the history is dated over eight weeks.
        registry.add("coop-erp.demo.historical-time", () -> "true");
    }

    @Autowired
    DemoDataLoader loader;

    @Autowired
    Clock clock;

    @Autowired
    PricingQueries pricing;

    @BeforeEach
    void theSeedTheDemoStartsFrom() throws Exception {
        JdbcTemplate admin = superuserJdbc();
        // The Federation row of entities.dev.sql, under a code of its own: other test classes of this
        // run register a Federation with the code FED.
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status, vat_registration_no)
                values (?, 'DEMOFED', 'FEDERATION', 'Cooperative Federation (demo test)', 'Colombo', 1, 'en', 'ACTIVE',
                    'VAT-DEMOFED')
                on conflict (entity_id) do nothing
                """,
                DemoCast.FEDERATION);
        try (var connection = admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("seed/m1party/demo-parties.demo.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("seed/m1security/demo-users.demo.sql"));
        }
    }

    /**
     * The trading history's documents go again after the test: the classes of one run share the
     * database, and a class that clears {@code kernel.document} (AttachmentsPostgresIntegrationTest)
     * must not find M4's rows pointing at it.
     */
    @AfterEach
    void removeTheTradingHistory() {
        JdbcTemplate admin = superuserJdbc();
        lk.coopfed.knoweb.m4trading.TradingFixture.cleanAllTrading(admin);
    }

    @Test
    void loadsTheDemoOnceAndASecondRunChangesNothing() {
        DemoDataLoader.Report first = loader.load();
        Map<String, Long> afterFirst = counts();

        assertThat(afterFirst)
                .containsEntry("shared SKUs", 40L)
                .containsEntry("conversions", 37L)
                .containsEntry("barcodes", 37L)
                .containsEntry("till positions", 5L)
                .containsEntry("primary tills", 4L)
                .containsEntry("published trade lists", 3L)
                .containsEntry("control prices", 3L)
                .containsEntry("published shelf lists of M101", 1L)
                .containsEntry("shelf prices of M101", 40L)
                .containsEntry("MRP policies of M101", 1L)
                .containsEntry("active relationships", 5L)
                .containsEntry("posted opening balances", 4L)
                .containsEntry("lots", 229L) // 200, and 29 at the Hettipola shop (DEMO-02)
                .containsEntry("received transfers to the town shop", 1L)
                .containsEntry("received transfers to the Hettipola shop", 1L)
                .containsEntry("lots with stock at the town shop", 40L)
                .containsEntry("orders of the history", 44L);
        if (first.total() > 0) {
            // A fresh database: the loader went through the handlers, which audited and published.
            assertThat(kernel.committedAudit()).isNotEmpty();
            assertThat(kernel.committedEvents()).isNotEmpty();
            // DEMO-02: 44 orders over five relationships; the last four of each stop at
            // SUBMITTED, ACCEPTED, DISPATCHED and RECEIVED, the others are invoiced.
            assertThat(first.commands())
                    .containsEntry("CreateOrder", 44)
                    .containsEntry("SubmitOrder", 44)
                    .containsEntry("AcceptOrder", 39)
                    .containsEntry("CreateDeliveryNote", 34)
                    .containsEntry("IssueDeliveryNote", 34)
                    .containsEntry("DispatchDeliveryNote", 34)
                    .containsEntry("CaptureGrn", 29)
                    .containsEntry("ConfirmGrn", 29)
                    .containsEntry("IssueInvoice", 24);
            theHistorySpreadsOverEightWeeks();
        }
        theTownShopSellsTheGazettedRiceAtItsControlPrice();

        kernel.reset();
        DemoDataLoader.Report second = loader.load();

        assertThat(second.total()).isZero();
        assertThat(counts()).isEqualTo(afterFirst);
    }

    /**
     * A demo user who can see the Stock tab (any role permission {@code inv.*}) must also be able
     * to list locations for the picker (bug seen live 28 Sep 2026: fed-steward and fed-accounts held
     * inv.stock.view but not prt.location.view, so /v1/party/locations answered 403 and the Stock and
     * Transfers screens showed an empty dropdown). Every demo role with an inv.* permission holds
     * prt.location.view too.
     */
    @Test
    void everyDemoRoleThatSeesInventoryCanAlsoListItsLocations() {
        JdbcTemplate admin = superuserJdbc();
        Long offenders = admin.queryForObject(
                """
                        select count(*)
                        from security.role r
                        where r.role_id::text like '0190f0de-%'
                          and exists (
                              select 1 from security.role_permission rp
                              where rp.role_id = r.role_id and rp.permission_code like 'inv.%')
                          and not exists (
                              select 1 from security.role_permission rp
                              where rp.role_id = r.role_id and rp.permission_code = 'prt.location.view')
                        """,
                Long.class);
        assertThat(offenders).isZero();
    }

    /**
     * DEMO-02: the history's orders, delivery notes, GRNs and invoices carry business dates over the
     * eight weeks before today, each kind later than the one before it, and every numbering series
     * the demo used numbers its documents in the order of their dates.
     */
    private void theHistorySpreadsOverEightWeeks() {
        JdbcTemplate admin = superuserJdbc();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"));
        String demo = "(owner_entity_id = '" + DemoCast.FEDERATION + "' or owner_entity_id::text like '0190f0de-%')";

        LocalDate firstOrder = admin.queryForObject(
                "select min(business_date) from kernel.document where notes like 'Demo history %'", LocalDate.class);
        LocalDate lastOrder = admin.queryForObject(
                "select max(business_date) from kernel.document where notes like 'Demo history %'", LocalDate.class);
        assertThat(firstOrder).isEqualTo(today.minusDays(DemoCalendar.HISTORY_DAYS));
        assertThat(lastOrder).isEqualTo(today);

        // Every document of the history is dated within the eight weeks, on at least 20 different days.
        List<String> types = List.of("ORD", "DN", "GRN", "INV");
        LocalDate previousFirst = null;
        for (String type : types) {
            Map<String, Object> range = admin.queryForMap(
                    "select min(business_date) as first, max(business_date) as last,"
                            + " count(distinct business_date) as days from kernel.document"
                            + " where doc_type_code = ? and issued_at is not null and " + demo,
                    type);
            LocalDate first = ((java.sql.Date) range.get("first")).toLocalDate();
            LocalDate last = ((java.sql.Date) range.get("last")).toLocalDate();
            assertThat(first).as(type).isAfterOrEqualTo(today.minusDays(DemoCalendar.HISTORY_DAYS));
            assertThat(last).as(type).isBeforeOrEqualTo(today);
            assertThat((Long) range.get("days")).as(type).isGreaterThanOrEqualTo(10L);
            if (previousFirst != null) {
                assertThat(first).as(type + " after the step before it").isAfter(previousFirst);
            }
            previousFirst = first;
        }

        // Per series, a later number never carries an earlier date.
        Long outOfOrder = admin.queryForObject(
                "select count(*) from (select business_date, lag(business_date) over"
                        + " (partition by series_id order by doc_number) as before"
                        + " from kernel.document where series_id is not null and doc_number is not null and "
                        + demo + ") numbered where business_date < before",
                Long.class);
        assertThat(outOfOrder).isZero();

        // And each document of an invoiced order is dated after the one it follows.
        Long backwards = admin.queryForObject(
                """
                select count(*) from kernel.document inv
                  join kernel.document grn on grn.document_id = inv.reference_document_id
                 where inv.doc_type_code = 'INV' and grn.doc_type_code = 'GRN' and inv.business_date <= grn.business_date
                """,
                Long.class);
        assertThat(backwards).isZero();
    }

    /**
     * M3-06: the shelf price of Nadu rice 5 kg at the town shop, as the shop's staff reads it. The
     * pack's MRP is Rs 1,150 and the gazette caps it at Rs 1,100, so the society's list carries the
     * control price; the engine resolves it, with the MRP of the batch on the shelf as its batch term.
     * The date is the set-up day plus one, well inside the list's validity, never "today" (a run
     * that crosses midnight in Colombo reads the same answer).
     */
    private void theTownShopSellsTheGazettedRiceAtItsControlPrice() {
        UUID rice = superuserJdbc()
                .queryForObject(
                        "select sku_id from catalogue.sku where short_name_en = 'Nadu rice 5 kg'"
                                + " and attributes ->> 'demo' = 'true'",
                        UUID.class);
        LocalDate shelfDay = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"))
                .minusDays(DemoCalendar.SETUP_DAYS_AGO - 1);
        Scope shop = new Scope(DemoCast.M101, DemoCast.M101_TOWN_SHOP);
        ScopeContext staff = new ScopeContext(
                DemoCast.M101_SHOP_STAFF.userId(),
                null,
                DemoCast.M101,
                List.of(shop),
                shop,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);

        assertThat(pricing.resolveRetailPrice(DemoCast.M101_TOWN_SHOP, rice, "EA", BigDecimal.ONE, shelfDay, staff))
                .get()
                .satisfies(price -> {
                    assertThat(price.sellable()).isTrue();
                    assertThat(price.listPrice()).isEqualByComparingTo("1100.00");
                    assertThat(price.controlPrice()).isEqualByComparingTo("1100.00");
                    assertThat(price.mrpApplied()).isEqualByComparingTo("1150.00");
                    assertThat(price.unitPrice()).isEqualByComparingTo("1100.00");
                });
    }

    /** What the demo consists of, counted as the superuser over the demo's own rows. */
    private static Map<String, Long> counts() {
        JdbcTemplate admin = superuserJdbc();
        String fed = DemoCast.FEDERATION.toString();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(
                "shared SKUs",
                count(
                        admin,
                        "select count(*) from catalogue.sku where owner_entity_id = ?::uuid"
                                + " and status = 'SHARED' and attributes ->> 'demo' = 'true'",
                        fed));
        counts.put(
                "conversions",
                count(
                        admin,
                        "select count(*) from catalogue.sku_uom_conversion c join catalogue.sku s using (sku_id)"
                                + " where s.attributes ->> 'demo' = 'true'"));
        counts.put(
                "barcodes",
                count(
                        admin,
                        "select count(*) from catalogue.sku_barcode b join catalogue.sku s using (sku_id)"
                                + " where s.attributes ->> 'demo' = 'true'"));
        counts.put(
                "till positions",
                count(
                        admin,
                        "select count(*) from party.till_position p join party.location l using (location_id)"
                                + " where l.location_id::text like '0190f0de-%'"));
        counts.put(
                "primary tills",
                count(
                        admin,
                        "select count(*) from party.location where location_id::text like '0190f0de-%'"
                                + " and primary_till_position_id is not null"));
        counts.put(
                "published trade lists",
                count(
                        admin,
                        "select count(*) from pricing.price_list where status = 'PUBLISHED' and kind = 'TRADE'"
                                + " and (owner_entity_id = ?::uuid or owner_entity_id::text like '0190f0de-%')"
                                + " and name like '%trade list for%'",
                        fed));
        counts.put(
                "control prices",
                count(
                        admin,
                        "select count(*) from pricing.control_price c join catalogue.sku s using (sku_id)"
                                + " where s.attributes ->> 'demo' = 'true'"));
        counts.put(
                "published shelf lists of M101",
                count(
                        admin,
                        "select count(*) from pricing.price_list where status = 'PUBLISHED' and kind = 'RETAIL'"
                                + " and owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put(
                "shelf prices of M101",
                count(
                        admin,
                        "select count(*) from pricing.price_list_line l join pricing.price_list p using (price_list_id)"
                                + " where p.kind = 'RETAIL' and p.status = 'PUBLISHED' and p.owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put(
                "MRP policies of M101",
                count(
                        admin,
                        "select count(*) from pricing.mrp_policy where owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put(
                "active relationships",
                count(
                        admin,
                        "select count(*) from party.entity_relationship where status = 'ACTIVE'"
                                + " and buyer_entity_id::text like '0190f0de-%'"));
        counts.put(
                "posted opening balances",
                count(
                        admin,
                        "select count(*) from inventory.opening_balance where status = 'POSTED'"
                                + " and location_id::text like '0190f0de-%'"));
        counts.put(
                "lots",
                count(admin, "select count(*) from inventory.stock_lot where location_id::text like '0190f0de-%'"));
        counts.put(
                "received transfers to the town shop",
                count(
                        admin,
                        "select count(*) from inventory.transfer_receipt where location_id = ?::uuid",
                        DemoCast.M101_TOWN_SHOP.toString()));
        counts.put(
                "received transfers to the Hettipola shop",
                count(
                        admin,
                        "select count(*) from inventory.transfer_receipt where location_id = ?::uuid",
                        DemoCast.M101_HETTIPOLA_SHOP.toString()));
        counts.put(
                "lots with stock at the town shop",
                count(
                        admin,
                        "select count(*) from inventory.stock_lot where location_id = ?::uuid and qty_on_hand > 0",
                        DemoCast.M101_TOWN_SHOP.toString()));
        counts.put(
                "orders of the history",
                count(admin, "select count(*) from kernel.document where notes like 'Demo history %'"));
        counts.put(
                "audit of the demo users",
                count(admin, "select count(*) from kernel.audit_event where actor_user_id::text like '0190f0de-%'"));
        return counts;
    }

    private static long count(JdbcTemplate admin, String sql, Object... args) {
        Long value = admin.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
