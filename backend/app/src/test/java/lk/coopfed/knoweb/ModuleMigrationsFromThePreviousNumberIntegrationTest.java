package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The demo server's situation (wave 2 fix plan, "a Flyway test start on a database at the previous
 * migration"): a database whose every stream stands at the number before this pull request's
 * migrations (kernel {@code V0085}, m1party {@code V0014}, m1security {@code V0018} since PR 11, m2catalogue
 * {@code V0007}, m3pricing {@code V0005}, m5inventory {@code V0006}, m7customers {@code V0002} since PR 09,
 * m8reporting {@code V0006} since PR 08) migrates to the new numbers
 * with Flyway strict (out of order false, as application.yml's default), in the order {@code
 * FlywayConfig} runs the streams. The streams depend on each other at run time only (a policy
 * naming a kernel function, M2's trigger calling an M5 function), so each later number must apply
 * on a database where the other streams are at their previous numbers too.
 */
class ModuleMigrationsFromThePreviousNumberIntegrationTest extends PostgresIntegrationTest {

    private static final String DATABASE = "zz_modules_from_previous";

    /** FlywayConfig's streams, in its order: location, schema, and the version before this change. */
    private static final Map<String, String> PREVIOUS = previous();

    private static Map<String, String> previous() {
        Map<String, String> streams = new LinkedHashMap<>();
        streams.put("kernel", "85");
        streams.put("hello", null);
        streams.put("m1party", "14");
        // Wave 2 PR 11 (m1security V0019): from V0018, where the demo server stands after PR 05.
        streams.put("m1security", "18");
        streams.put("m2catalogue", "7");
        streams.put("m3pricing", "5");
        // Wave 2, PR 07: m4trading V0010 (extension rows until issue) on a database at V0009.
        streams.put("m4trading", "9");
        streams.put("m5inventory", "6");
        streams.put("m6pos", null);
        streams.put("m7customers", "2");
        // Wave 2, PR 08: m8reporting V0007 (projections by location, event and line) from V0006.
        streams.put("m8reporting", "6");
        streams.put("m9integration", null);
        return streams;
    }

    private static final Map<String, String> SCHEMA = Map.ofEntries(
            Map.entry("kernel", "kernel"),
            Map.entry("hello", "hello"),
            Map.entry("m1party", "party"),
            Map.entry("m1security", "security"),
            Map.entry("m2catalogue", "catalogue"),
            Map.entry("m3pricing", "pricing"),
            Map.entry("m4trading", "trading"),
            Map.entry("m5inventory", "inventory"),
            Map.entry("m6pos", "pos"),
            Map.entry("m7customers", "customers"),
            Map.entry("m8reporting", "reporting"),
            Map.entry("m9integration", "integration"));

    @AfterEach
    void dropTheDatabase() {
        superuserJdbc().execute("drop database if exists " + DATABASE + " with (force)");
    }

    @Test
    void everyStreamAtItsPreviousNumberMigratesToTheNewOneStrictly() {
        superuserJdbc().execute("drop database if exists " + DATABASE + " with (force)");
        superuserJdbc().execute("create database " + DATABASE + " owner coop_migrator");
        String url = POSTGRES.getJdbcUrl().replace("/coop_erp", "/" + DATABASE);

        for (Map.Entry<String, String> stream : PREVIOUS.entrySet()) {
            FluentConfiguration configuration = flyway(url, stream.getKey());
            if (stream.getValue() != null) {
                configuration.target(MigrationVersion.fromVersion(stream.getValue()));
            }
            Flyway before = configuration.load();
            before.migrate();
            if (stream.getValue() != null) {
                assertThat(before.info().current().getVersion())
                        .as(stream.getKey() + " at its previous number")
                        .isEqualByComparingTo(MigrationVersion.fromVersion(stream.getValue()));
            }
        }

        // The demo server's database, with a quarantined ceiling of the Federation's and a lot, so
        // the altered policies and functions apply over rows.
        JdbcTemplate admin =
                new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.update(
                "insert into kernel.system_identity (singleton, entity_id) values (true, ?::uuid)", TEST_FEDERATION_ID);

        // Two lines of a till receipt projected before V0007: keyed by line_id, no number.
        String receipt = "0190e8aa-0000-7000-8000-000000000001";
        for (String line : List.of("0190e8aa-0000-7000-8000-0000000000a2", "0190e8aa-0000-7000-8000-0000000000a1")) {
            admin.update(
                    """
                    insert into reporting.shop_sale_line_fact
                           (line_id, receipt_id, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                    values (?::uuid, ?::uuid, ?::uuid, gen_random_uuid(), date '2026-09-26', gen_random_uuid(), 1, 10.00)
                    """,
                    line,
                    receipt,
                    TEST_FEDERATION_ID);
        }

        Map<String, Integer> after = new LinkedHashMap<>();
        for (String stream : PREVIOUS.keySet()) {
            Flyway latest = flyway(url, stream).load();
            latest.migrate();
            after.put(
                    stream,
                    Integer.parseInt(latest.info().current().getVersion().getVersion()));
        }

        assertThat(after)
                .containsEntry("kernel", 86)
                .containsEntry("m1party", 15)
                .containsEntry("m1security", 19)
                .containsEntry("m2catalogue", 8)
                .containsEntry("m3pricing", 6)
                .containsEntry("m5inventory", 7);

        // What the new numbers leave in place, read from the catalogue.
        List<String> functions = admin.queryForList(
                """
                select n.nspname || '.' || p.proname
                  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                 where n.nspname || '.' || p.proname in ('kernel.document_open_for_write',
                       'party.caller_trades_with', 'inventory.caller_holds_lot_of')
                 order by 1
                """,
                String.class);
        assertThat(functions)
                .containsExactly(
                        "inventory.caller_holds_lot_of", "kernel.document_open_for_write", "party.caller_trades_with");
        assertThat(admin.queryForObject(
                        "select has_function_privilege('coop_app', 'inventory.entity_holds_lot_of(uuid, uuid)', 'EXECUTE')",
                        Boolean.class))
                .as("the two-argument lot question is the migrator's alone")
                .isFalse();
        assertThat(admin.queryForObject(
                        "select with_check from pg_policies where schemaname = 'pricing'"
                                + " and tablename = 'control_price' and policyname = 'own_write'",
                        String.class))
                .contains("kernel.system_entity()");

        // Wave 2, PR 07: m4trading V0010 over a database at V0009.
        assertThat(after).containsEntry("m4trading", 10);
        assertThat(admin.queryForObject(
                        "select with_check from pg_policies where schemaname = 'trading'"
                                + " and tablename = 'doc_grn_line' and policyname = 'document_write'",
                        String.class))
                .contains("kernel.document_open_for_write(document_id)");

        // Wave 2, PR 08: m8reporting V0007 over a database at V0006 with rows in it. The old lines
        // are numbered in their line_id order under the new key, as the migrator (not a superuser)
        // could only do with the table's FORCE lifted for that statement, and FORCE is back.
        assertThat(after).containsEntry("m8reporting", 7);
        assertThat(admin.queryForList(
                        "select line_id::text || ':' || line_no from reporting.shop_sale_line_fact order by line_no",
                        String.class))
                .containsExactly("0190e8aa-0000-7000-8000-0000000000a1:1", "0190e8aa-0000-7000-8000-0000000000a2:2");
        assertThat(admin.queryForObject(
                        "select relforcerowsecurity from pg_class where oid = 'reporting.shop_sale_line_fact'::regclass",
                        Boolean.class))
                .isTrue();
        assertThat(admin.queryForObject(
                        "select qual from pg_policies where schemaname = 'reporting'"
                                + " and tablename = 'trade_document_event' and policyname = 'own_read'",
                        String.class))
                .contains("kernel.scope_location()");
        assertThat(admin.queryForObject("select to_regclass('reporting.credit_limit_fact')::text", String.class))
                .isEqualTo("reporting.credit_limit_fact");
    }

    /** One stream as FlywayConfig runs it, strict. */
    private static FluentConfiguration flyway(String url, String stream) {
        String schema = SCHEMA.get(stream);
        return Flyway.configure()
                .dataSource(url, "coop_migrator", "coop_migrator")
                .locations("classpath:db/migration/" + stream)
                .schemas(schema)
                .defaultSchema(schema)
                .outOfOrder(false)
                .validateOnMigrate(true);
    }
}
