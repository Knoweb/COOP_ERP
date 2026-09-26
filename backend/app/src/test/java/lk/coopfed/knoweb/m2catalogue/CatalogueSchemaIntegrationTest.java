package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import lk.coopfed.knoweb.kernel.api.Ids;
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

    /** The policies of every table M2-01 creates (V0001), and batch_key of the review (V0003). */
    private static final Map<String, Set<String>> POLICIES = Map.ofEntries(
            Map.entry("uom", Set.of("authenticated_read", "seed_reference")),
            Map.entry("tax_category", with("own_update", "authenticated_read", "seed_reference")),
            Map.entry("tax_rate", with("own_update", "authenticated_read", "seed_reference")),
            Map.entry("sku", with("own_update", "shared_read")),
            Map.entry("sku_uom_conversion", with("own_update", "shared_read")),
            Map.entry("sku_barcode", with("own_update", "shared_read")),
            Map.entry("tag", with("own_update", "governed_read", "seed_reference")),
            Map.entry("sku_tag", with("shared_read")),
            Map.entry("batch", with("own_update", "shared_read")),
            Map.entry("batch_key", with("own_update", "shared_read")));

    /** The default partition alone admits the migrator, which moves rows out of it (V0003). */
    private static final String DEFAULT_PARTITION = "batch_default";

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
            Set<String> expected = new TreeSet<>(POLICIES.get(expectedOf));
            if (name.equals(DEFAULT_PARTITION)) {
                expected.add("partition_move");
            }

            assertThat(table.get("relrowsecurity")).as(name + " enabled").isEqualTo(true);
            assertThat(table.get("relforcerowsecurity")).as(name + " forced").isEqualTo(true);
            assertThat(new TreeSet<>(policiesOf(db, name)))
                    .as(name + " policies")
                    .isEqualTo(expected);
        }
    }

    @Test
    void theChildRowsOfAnItemFollowItsOwner() {
        // V0003: own_write on the child tables asks catalogue.sku who owns the parent.
        List<Map<String, Object>> policies = superuserJdbc()
                .queryForList(
                        """
                select tablename, with_check from pg_policies
                 where schemaname = 'catalogue' and policyname = 'own_write'
                   and tablename in ('sku_uom_conversion', 'sku_barcode', 'sku_tag')
                """);

        assertThat(policies).hasSize(3);
        for (Map<String, Object> policy : policies) {
            assertThat((String) policy.get("with_check"))
                    .as(policy.get("tablename") + ".own_write")
                    .contains("FROM catalogue.sku s")
                    .contains("s.owner_entity_id = kernel.scope_entity()");
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
        // V0003: UPDATE only on the columns the handler specifications change (22A section 6).
        assertThat(may(db, "tax_rate", "UPDATE"))
                .as("a published rate is never rewritten")
                .isFalse();
        assertThat(mayUpdateColumn(db, "tax_rate", "effective_to")).isTrue();
        assertThat(mayUpdateColumn(db, "tax_rate", "rate_percent")).isFalse();
        assertThat(may(db, "sku_uom_conversion", "UPDATE")).isFalse();
        assertThat(mayUpdateColumn(db, "sku_uom_conversion", "effective_to")).isTrue();
        assertThat(mayUpdateColumn(db, "sku_uom_conversion", "factor_to_base")).isFalse();
        assertThat(may(db, "sku_barcode", "UPDATE")).isFalse();
        assertThat(mayUpdateColumn(db, "sku_barcode", "status")).isTrue();
        assertThat(mayUpdateColumn(db, "sku_barcode", "batch_id")).isTrue();
        assertThat(mayUpdateColumn(db, "sku_barcode", "sku_id")).isFalse();
        assertThat(may(db, "batch_key", "UPDATE")).isFalse();
        assertThat(mayUpdateColumn(db, "batch_key", "batch_id")).isTrue();
        assertThat(mayUpdateColumn(db, "batch_key", "batch_no")).isFalse();
    }

    @Test
    void batchIsPartitionedByMonthWithThisMonthAndThreeMoreReadyAndADefault() {
        JdbcTemplate db = superuserJdbc();

        assertThat(db.queryForObject("select pg_get_partkeydef('catalogue.batch'::regclass)", String.class))
                .isEqualTo("RANGE (created_at)");

        List<String> partitions = partitionsOfBatch(db);
        List<String> expected = IntStream.rangeClosed(0, 3)
                .mapToObj(CatalogueSchemaIntegrationTest::partitionOfMonth)
                .toList();
        assertThat(partitions).containsAll(expected).contains(DEFAULT_PARTITION);
        assertThat(db.queryForObject(
                        "select pg_get_expr(c.relpartbound, c.oid) from pg_class c where c.oid = 'catalogue.batch_default'::regclass",
                        String.class))
                .isEqualTo("DEFAULT");
        // V0003: a look-up by id alone no longer scans every partition.
        assertThat(db.queryForList(
                        "select indexdef from pg_indexes where schemaname = 'catalogue' and tablename = 'batch'",
                        String.class))
                .anyMatch(definition -> definition.endsWith("ON ONLY catalogue.batch USING btree (batch_id)"));
    }

    @Test
    void thePartitionFunctionIsIdempotentAndReportsWhatItCreated() {
        JdbcTemplate db = superuserJdbc();
        List<String> before = partitionsOfBatch(db);

        assertThat(db.queryForObject("select catalogue.ensure_batch_partitions(3)", Integer.class))
                .isZero();
        assertThat(partitionsOfBatch(db)).isEqualTo(before);

        String fourthMonth = partitionOfMonth(4);
        db.execute("drop table if exists catalogue." + fourthMonth);
        try {
            assertThat(db.queryForObject("select catalogue.ensure_batch_partitions(4)", Integer.class))
                    .isEqualTo(1);
            assertThat(partitionsOfBatch(db)).contains(fourthMonth);
            assertThat(new TreeSet<>(policiesOf(db, fourthMonth))).isEqualTo(new TreeSet<>(POLICIES.get("batch")));
        } finally {
            dropTheMonthsBeyondTheMigration(db);
        }
    }

    @Test
    void aRowOfAMonthWithoutAPartitionWaitsInTheDefaultAndMovesWhenThePartitionIsCreated() {
        JdbcTemplate db = superuserJdbc();
        String sixthMonth = partitionOfMonth(6);
        UUID sku = Ids.next();
        UUID batch = Ids.next();
        db.execute("select catalogue.ensure_batch_partitions(5)"); // so that only the sixth month is missing
        db.execute("drop table if exists catalogue." + sixthMonth);
        db.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, short_name_en, base_uom_code, tax_category_id)"
                        + " values (?, 'T-PART', ?, 'Partition test', 'EA', ?)",
                sku,
                TEST_FEDERATION,
                Ids.next());
        try {
            // created_at in a month that has no partition: caught by the default partition.
            db.update(
                    "insert into catalogue.batch (batch_id, sku_id, batch_no, created_at, owner_entity_id)"
                            + " values (?, ?, 'B-LATE', date_trunc('month', now() at time zone 'utc') + interval '6 months' + interval '1 day', ?)",
                    batch,
                    sku,
                    TEST_FEDERATION);
            assertThat(db.queryForObject(
                            "select count(*) from catalogue.batch_default where batch_id = ?", Integer.class, batch))
                    .isEqualTo(1);

            assertThat(db.queryForObject("select catalogue.ensure_batch_partitions(6)", Integer.class))
                    .isEqualTo(1);

            assertThat(db.queryForObject(
                            "select count(*) from catalogue.batch_default where batch_id = ?", Integer.class, batch))
                    .isZero();
            assertThat(db.queryForObject(
                            "select count(*) from catalogue." + sixthMonth + " where batch_id = ?",
                            Integer.class,
                            batch))
                    .isEqualTo(1);
            assertThat(db.queryForObject(
                            "select count(*) from catalogue.batch_key where batch_id = ?", Integer.class, batch))
                    .as("the identity row was written once, when the batch arrived")
                    .isEqualTo(1);
        } finally {
            db.update("delete from catalogue.batch where batch_id = ?", batch);
            db.update("delete from catalogue.batch_key where batch_id = ?", batch);
            db.update("delete from catalogue.sku where sku_id = ?", sku);
            dropTheMonthsBeyondTheMigration(db);
        }
    }

    @Test
    void twoInstancesRollingOverTogetherBothSucceed() throws Exception {
        JdbcTemplate db = superuserJdbc();
        String fifthMonth = partitionOfMonth(5);
        db.execute("select catalogue.ensure_batch_partitions(4)"); // so that only the fifth month is missing
        db.execute("drop table if exists catalogue." + fifthMonth);
        try {
            ExecutorService instances = Executors.newFixedThreadPool(2);
            List<Future<Integer>> runs = instances.invokeAll(List.of(
                    () -> superuserJdbc().queryForObject("select catalogue.ensure_batch_partitions(5)", Integer.class),
                    () -> superuserJdbc()
                            .queryForObject("select catalogue.ensure_batch_partitions(5)", Integer.class)));
            instances.shutdown();

            int created = 0;
            for (Future<Integer> run : runs) {
                created += run.get(); // neither call fails with "already exists"
            }
            assertThat(created).isEqualTo(1);
            assertThat(partitionsOfBatch(db)).contains(fifthMonth);
        } finally {
            dropTheMonthsBeyondTheMigration(db);
        }
    }

    /** Back to what the migration left: this month and the three after it. */
    private static void dropTheMonthsBeyondTheMigration(JdbcTemplate db) {
        for (int month = 4; month <= 6; month++) {
            db.execute("drop table if exists catalogue." + partitionOfMonth(month));
        }
    }

    private static String partitionOfMonth(int monthsAhead) {
        return "batch_"
                + YearMonth.now(ZoneOffset.UTC).plusMonths(monthsAhead).format(DateTimeFormatter.ofPattern("yyyy_MM"));
    }

    private static boolean mayUpdateColumn(JdbcTemplate db, String table, String column) {
        return db.queryForObject(
                "select has_column_privilege('app_rw', ?, ?, 'UPDATE')", Boolean.class, "catalogue." + table, column);
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
