package lk.coopfed.knoweb.m4trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema rules of m4trading V0001, read from the catalogue of the migrated database (the
 * style of {@code CatalogueSchemaIntegrationTest}): which tables exist, that every one has
 * row-level security enabled and forced, which policies each carries (the rows of a document
 * follow the header; the seller's own tables take the template), and what the application role
 * may do. {@code TradingRlsIntegrationTest} proves what the policies admit; this proves they are
 * there.
 */
class TradingSchemaIntegrationTest extends PostgresIntegrationTest {

    /** The rows of a document (RLS_POLICY_TEMPLATE.md, "The rows of a document"). */
    private static final Set<String> FOLLOWS_HEADER = Set.of("document_read", "document_write");

    private static final Set<String> FOLLOWS_HEADER_UPDATED =
            Set.of("document_read", "document_write", "document_update");

    private static final Set<String> TEMPLATE = Set.of("own_read", "own_write", "fed_view", "ext_view");

    private static final Set<String> TEMPLATE_WITH_PARTY =
            Set.of("own_read", "own_write", "party_read", "fed_view", "ext_view");

    private static final Map<String, Set<String>> POLICIES = Map.ofEntries(
            Map.entry("doc_order", FOLLOWS_HEADER),
            Map.entry("doc_order_line", FOLLOWS_HEADER_UPDATED),
            Map.entry("allocation_run", TEMPLATE),
            Map.entry("order_allocation", TEMPLATE_WITH_PARTY),
            Map.entry("order_allocation_line", with(TEMPLATE_WITH_PARTY, "own_update")),
            Map.entry("doc_delivery", FOLLOWS_HEADER_UPDATED),
            Map.entry("doc_delivery_drop", FOLLOWS_HEADER_UPDATED),
            Map.entry("doc_delivery_line", FOLLOWS_HEADER),
            Map.entry("doc_grn", FOLLOWS_HEADER_UPDATED),
            Map.entry("doc_grn_line", FOLLOWS_HEADER_UPDATED),
            Map.entry("doc_discrepancy", FOLLOWS_HEADER),
            Map.entry("doc_discrepancy_line", FOLLOWS_HEADER),
            Map.entry("doc_invoice", FOLLOWS_HEADER_UPDATED),
            Map.entry("posting_map", Set.of("reference_read", "seed_reference")));

    /** The columns app_rw may update, per table; a table not named here grants no UPDATE. */
    private static final Map<String, Set<String>> UPDATABLE = Map.of(
            "doc_order_line", Set.of("cancelled_qty"),
            "doc_delivery", Set.of("vehicle_ref", "driver_user_id", "driver_name", "dispatched_at", "route_ref"),
            "doc_delivery_drop",
                    Set.of(
                            "status",
                            "arrived_at",
                            "pod_kind",
                            "pod_signature_key",
                            "pod_photo_key",
                            "delivered_by",
                            "undelivered_reason"),
            "doc_grn", Set.of("confirmed_by", "confirmed_at"),
            "doc_grn_line", Set.of("batch_id", "unit_cost"),
            "order_allocation_line", Set.of("fulfilled_qty"),
            "doc_invoice", Set.of("print_object_key"));

    @Test
    void theTradingSchemaHoldsTheTablesOfM401() {
        List<String> tables = superuserJdbc()
                .queryForList(
                        """
                        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                         where n.nspname = 'trading' and c.relkind in ('r', 'p')
                           and c.relname <> 'flyway_schema_history'
                        """,
                        String.class);

        assertThat(new TreeSet<>(tables)).isEqualTo(new TreeSet<>(POLICIES.keySet()));
    }

    @Test
    void everyTableHasForcedRowLevelSecurityAndItsPolicies() {
        JdbcTemplate db = superuserJdbc();
        for (Map.Entry<String, Set<String>> table : POLICIES.entrySet()) {
            Map<String, Object> security = db.queryForMap(
                    "select relrowsecurity, relforcerowsecurity from pg_class where oid = ?::regclass",
                    "trading." + table.getKey());
            assertThat(security.get("relrowsecurity"))
                    .as(table.getKey() + " enabled")
                    .isEqualTo(true);
            assertThat(security.get("relforcerowsecurity"))
                    .as(table.getKey() + " forced")
                    .isEqualTo(true);

            List<String> policies = db.queryForList(
                    "select policyname from pg_policies where schemaname = 'trading' and tablename = ?",
                    String.class,
                    table.getKey());
            assertThat(new TreeSet<>(policies)).as(table.getKey()).isEqualTo(new TreeSet<>(table.getValue()));
        }
    }

    @Test
    void theRowsOfADocumentAreDecidedByTheirHeader() {
        JdbcTemplate db = superuserJdbc();
        List<Map<String, Object>> policies = db.queryForList(
                """
                select tablename, policyname, coalesce(qual, '') as qual, coalesce(with_check, '') as with_check
                  from pg_policies where schemaname = 'trading' and policyname like 'document_%'
                """);
        assertThat(policies).isNotEmpty();
        for (Map<String, Object> policy : policies) {
            String where = policy.get("tablename") + "." + policy.get("policyname");
            if ("document_read".equals(policy.get("policyname"))) {
                assertThat((String) policy.get("qual")).as(where).contains("kernel.document_visible(document_id)");
            } else {
                assertThat((String) policy.get("with_check")).as(where).contains("kernel.document_owned(document_id)");
            }
        }
    }

    @Test
    void theApplicationRoleInsertsAndReadsEverywhereUpdatesByColumnAndDeletesNothing() {
        JdbcTemplate db = superuserJdbc();
        for (String table : POLICIES.keySet()) {
            String qualified = "trading." + table;
            boolean reference = "posting_map".equals(table);
            assertThat(db.queryForObject("select has_table_privilege('app_rw', ?, 'SELECT')", Boolean.class, qualified))
                    .as(table + " select")
                    .isTrue();
            assertThat(db.queryForObject("select has_table_privilege('app_rw', ?, 'INSERT')", Boolean.class, qualified))
                    .as(table + " insert")
                    .isEqualTo(!reference);
            assertThat(db.queryForObject("select has_table_privilege('app_rw', ?, 'DELETE')", Boolean.class, qualified))
                    .as(table + " delete")
                    .isFalse();
            assertThat(db.queryForObject(
                            "select has_table_privilege('app_rw', ?, 'TRUNCATE')", Boolean.class, qualified))
                    .as(table + " truncate")
                    .isFalse();

            List<String> updatable = db.queryForList(
                    """
                    select column_name from information_schema.column_privileges
                     where table_schema = 'trading' and table_name = ? and grantee = 'app_rw'
                       and privilege_type = 'UPDATE'
                    """,
                    String.class,
                    table);
            assertThat(new TreeSet<>(updatable))
                    .as(table + " updatable columns")
                    .isEqualTo(new TreeSet<>(UPDATABLE.getOrDefault(table, Set.of())));
        }
        assertThat(db.queryForObject(
                        "select has_table_privilege('app_seed', 'trading.posting_map', 'INSERT')", Boolean.class))
                .isTrue();
    }

    private static Set<String> with(Set<String> base, String... more) {
        Set<String> all = new HashSet<>(base);
        all.addAll(List.of(more));
        return all;
    }
}
