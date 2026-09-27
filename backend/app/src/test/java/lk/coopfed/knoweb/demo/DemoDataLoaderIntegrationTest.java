package lk.coopfed.knoweb.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
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
    }

    @Autowired
    DemoDataLoader loader;

    @BeforeEach
    void theSeedTheDemoStartsFrom() throws Exception {
        JdbcTemplate admin = superuserJdbc();
        // The Federation row of entities.dev.sql, under a code of its own: other test classes of this
        // run register a Federation with the code FED.
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                values (?, 'DEMOFED', 'FEDERATION', 'Cooperative Federation (demo test)', 'Colombo', 1, 'en', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                DemoCast.FEDERATION);
        try (var connection = admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("seed/m1party/demo-parties.demo.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("seed/m1security/demo-users.demo.sql"));
        }
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
                .containsEntry("active relationships", 5L)
                .containsEntry("posted opening balances", 3L)
                .containsEntry("lots", 120L);
        if (first.total() > 0) {
            // A fresh database: the loader went through the handlers, which audited and published.
            assertThat(kernel.committedAudit()).isNotEmpty();
            assertThat(kernel.committedEvents()).isNotEmpty();
        }

        kernel.reset();
        DemoDataLoader.Report second = loader.load();

        assertThat(second.total()).isZero();
        assertThat(counts()).isEqualTo(afterFirst);
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
                "audit of the demo users",
                count(admin, "select count(*) from kernel.audit_event where actor_user_id::text like '0190f0de-%'"));
        return counts;
    }

    private static long count(JdbcTemplate admin, String sql, Object... args) {
        Long value = admin.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
