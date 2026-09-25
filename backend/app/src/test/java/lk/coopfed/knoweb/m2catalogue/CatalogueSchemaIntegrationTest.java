package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema rules of m2catalogue V0001, read from the catalogue of the migrated database (the
 * style of {@code SchemaRulesIntegrationTest}, narrowed to the catalogue schema and made
 * exact): which tables exist, that every one of them, batch partitions included, has row-level
 * security enabled and forced, which policies each carries, that own_read and own_write test
 * the scope class, and what the application role may do. {@code CatalogueRlsIntegrationTest}
 * proves what the policies admit; this test proves that they are there.
 */
class CatalogueSchemaIntegrationTest extends PostgresIntegrationTest {

    private static final Set<String> TEMPLATE = Set.of("own_read", "own_write", "fed_view", "ext_view");

    /** The policies of every table M2-01 creates. */
    private static final Map<String, Set<String>> POLICIES = Map.of(
            "uom", Set.of("authenticated_read", "seed_reference"),
            "tax_category", with("own_update", "authenticated_read", "seed_reference"),
            "tax_rate", with("own_update", "authenticated_read", "seed_reference"),
            "sku", with("own_update", "shared_read"),
            "sku_uom_conversion", with("own_update", "shared_read"),
            "sku_barcode", with("own_update", "shared_read"),
            "tag", with("own_update", "governed_read", "seed_reference"),
            "sku_tag", with("shared_read"),
            "batch", with("own_update", "shared_read"));

    @Test
    void theCatalogueSchemaHoldsTheTablesOfM201() {
        List<String> tables = superuserJdbc()
                .queryForList(
                        """
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'catalogue' and c.relkind in ('r', 'p') and not c.relispartition
                   and c.relname <> 'flyway_schema_history'
                """,
                        String.class);

        assertThat(new TreeSet<>(tables)).isEqualTo(new TreeSet<>(POLICIES.keySet()));
    }

    @Test
    void everyTableAndEveryBatchPartitionHasForcedRowLevelSecurityAndItsPolicies() {
        JdbcTemplate db = superuserJdbc();
        List<Map<String, Object>> tables = db.queryForList(
                """
                select c.relname, c.relrowsecurity, c.relforcerowsecurity, c.relispartition
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'catalogue' and c.relkind in ('r', 'p')
                   and c.relname <> 'flyway_schema_history'
                """);

        assertThat(tables).hasSizeGreaterThan(POLICIES.size()); // the partitions of batch are counted too
        for (Map<String, Object> table : tables) {
            String name = (String) table.get("relname");
            String expectedOf = (Boolean) table.get("relispartition") ? "batch" : name;

            assertThat(table.get("relrowsecurity")).as(name + " enabled").isEqualTo(true);
            assertThat(table.get("relforcerowsecurity")).as(name + " forced").isEqualTo(true);
            assertThat(new TreeSet<>(policiesOf(db, name)))
                    .as(name + " policies")
                    .isEqualTo(new TreeSet<>(POLICIES.get(expectedOf)));
        }
    }

    @Test
    void ownReadAndOwnWriteTestTheScopeClass() {
        List<Map<String, Object>> policies = superuserJdbc()
                .queryForList(
                        """
                select tablename, policyname, coalesce(qual, with_check) as expression
                  from pg_policies
                 where schemaname = 'catalogue' and policyname in ('own_read', 'own_write', 'own_update')
                """);

        assertThat(policies).isNotEmpty();
        for (Map<String, Object> policy : policies) {
            assertThat((String) policy.get("expression"))
                    .as(policy.get("tablename") + "." + policy.get("policyname"))
                    .contains("scope_class() = 'OWN'")
                    .contains("owner_entity_id = kernel.scope_entity()");
        }
    }

    @Test
    void theApplicationRoleMayNeverDeleteAndMayUpdateOnlyWhatTheGuideAllows() {
        JdbcTemplate db = superuserJdbc();

        for (String table : POLICIES.keySet()) {
            assertThat(may(db, table, "DELETE")).as(table + " DELETE").isFalse();
            assertThat(may(db, table, "TRUNCATE")).as(table + " TRUNCATE").isFalse();
            assertThat(may(db, table, "SELECT")).as(table + " SELECT").isTrue();
        }
        // Units are reference data: read only.
        assertThat(may(db, "uom", "INSERT")).isFalse();
        assertThat(may(db, "uom", "UPDATE")).isFalse();
        // A tag assignment is never changed.
        assertThat(may(db, "sku_tag", "UPDATE")).isFalse();
        // 22A section 3: "batch: INSERT + UPDATE(status) only".
        assertThat(may(db, "batch", "INSERT")).isTrue();
        assertThat(may(db, "batch", "UPDATE")).as("UPDATE on the whole row").isFalse();
        assertThat(db.queryForObject(
                        "select has_column_privilege('app_rw', 'catalogue.batch', 'status', 'UPDATE')", Boolean.class))
                .isTrue();
        assertThat(db.queryForObject(
                        "select has_column_privilege('app_rw', 'catalogue.batch', 'printed_mrp', 'UPDATE')",
                        Boolean.class))
                .as("a printed MRP is corrected by a new batch, never edited")
                .isFalse();
    }

    @Test
    void batchIsPartitionedByMonthWithThisMonthAndThreeMoreReady() {
        JdbcTemplate db = superuserJdbc();

        assertThat(db.queryForObject("select pg_get_partkeydef('catalogue.batch'::regclass)", String.class))
                .isEqualTo("RANGE (created_at)");

        List<String> partitions = partitionsOfBatch(db);
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy_MM");
        List<String> expected = IntStream.rangeClosed(0, 3)
                .mapToObj(offset -> "batch_" + now.plusMonths(offset).format(format))
                .toList();
        assertThat(partitions).containsAll(expected);
    }

    @Test
    void thePartitionFunctionIsIdempotent() {
        JdbcTemplate db = superuserJdbc();
        List<String> before = partitionsOfBatch(db);

        db.execute("select catalogue.ensure_batch_partitions(3)");

        assertThat(partitionsOfBatch(db)).isEqualTo(before);
    }

    private static List<String> partitionsOfBatch(JdbcTemplate db) {
        return db.queryForList(
                """
                select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
                 where i.inhparent = 'catalogue.batch'::regclass order by 1
                """,
                String.class);
    }

    private static List<String> policiesOf(JdbcTemplate db, String table) {
        return db.queryForList(
                "select policyname from pg_policies where schemaname = 'catalogue' and tablename = ?",
                String.class,
                table);
    }

    private static boolean may(JdbcTemplate db, String table, String privilege) {
        return db.queryForObject(
                "select has_table_privilege('app_rw', ?, ?)", Boolean.class, "catalogue." + table, privilege);
    }

    private static Set<String> with(String... extra) {
        Set<String> policies = new TreeSet<>(TEMPLATE);
        policies.addAll(List.of(extra));
        return policies;
    }
}
