package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema test of 17A section 6.2 ("a table without a policy fails the schema test") and the
 * done criterion of S0-04 ("fresh DB migrates to baseline; schema list matches").
 *
 * <p>It reads the catalogue of the migrated database, so it sees every table of every module,
 * the ones a scaffolded module adds included, and nobody has to register anything:
 *
 * <ul>
 *   <li>the schemas are exactly the twelve of 17A section 6.1;</li>
 *   <li>every table has row-level security enabled and forced, and at least one policy.
 *       Without it every tenant reads every row of that table, and no other test notices,
 *       because a test of module A does not look at the tables of module B;</li>
 *   <li>the application role {@code app_rw} may not DELETE or TRUNCATE anywhere, and may not
 *       create objects (AGENTS.md; 17A section 6.2: "no DELETE anywhere for app_rw"), with the
 *       four exceptions of {@link #DELETE_BY_DESIGN};</li>
 * </ul>
 *
 * A table every tenant may read (a reference table) still needs a policy; it says so
 * explicitly: {@code CREATE POLICY everyone_reads ON ... FOR SELECT TO app_rw USING (true)}.
 */
class SchemaRulesIntegrationTest extends PostgresIntegrationTest {

    private static final Set<String> SCHEMAS = Set.of(
            "kernel",
            "hello",
            "party",
            "security",
            "catalogue",
            "pricing",
            "trading",
            "inventory",
            "pos",
            "customers",
            "reporting",
            "integration");

    /**
     * The only tables app_rw may delete from, each named by its implementation guide and each
     * delete made by a command handler that audits it and publishes an event (m1security
     * V0010): a role assignment is revoked by deleting it (21A section 6, RevokeRole: "the one
     * DELETE M1 performs; audited"), a role's permission set is replaced (AmendRole), and an
     * entity removes a separation-of-duties pair it added itself. None is a document, a ledger
     * or an audit row. CR-21A-3 (accepted 27 September 2026) settled the three: each is a current
     * fact whose history is its audit record, and a table may join this list only when its
     * implementation guide names the command that deletes from it. Adding a table here is a
     * design decision, not a fix for a red test.
     *
     * <p>pricing.price_list_line (M3-04, decided 27 September 2026 on the architect's delegation):
     * 23A section 7 names SetLines, "upsert draft lines", which replaces the lines of a DRAFT
     * list; a removed draft line is deleted. The trigger pricing.line_of_a_draft refuses the
     * delete of a line whose list is not a draft, so a published line is never deleted, and
     * SetLines audits PRICELIST_LINES_SET and publishes price_list.lines_set.v1.
     */
    static final Set<String> DELETE_BY_DESIGN =
            Set.of("security.user_role", "security.role_permission", "security.sod_pair", "pricing.price_list_line");

    @Test
    void theSchemasAreTheTwelveOfTheDesign() {
        List<String> found = superuserJdbc()
                .queryForList(
                        """
                select nspname from pg_namespace
                 where nspname !~ '^pg_' and nspname not in ('information_schema', 'public')
                """,
                        String.class);

        assertThat(new TreeSet<>(found)).isEqualTo(new TreeSet<>(SCHEMAS));
    }

    @Test
    void everyTableIsProtectedAndTheApplicationRoleCannotDelete() {
        assertThat(problemsOf(superuserJdbc())).isEmpty();
    }

    /**
     * The kernel's upload ledger (kernel V0060, CR-19A-7): protected like every table, and app_rw
     * may change only what a renewal and a settlement change. The key, the module, the owner, the
     * type and the creation time of an upload are nobody's to change; the clean-up's mark of a
     * deleted object (kernel V0063) is the one change a settled row admits.
     */
    @Test
    void theObjectLedgerIsProtectedAndOnlyItsSettlingColumnsAreUpdatable() {
        JdbcTemplate db = superuserJdbc();
        assertThat(problemsOf(db)).noneMatch(problem -> problem.startsWith("kernel.object_upload:"));
        assertThat(db.queryForObject(
                        "select relrowsecurity and relforcerowsecurity from pg_class where oid = 'kernel.object_upload'::regclass",
                        Boolean.class))
                .isTrue();
        List<String> updatable = db.queryForList(
                """
                select column_name from information_schema.column_privileges
                 where table_schema = 'kernel' and table_name = 'object_upload'
                   and grantee = 'app_rw' and privilege_type = 'UPDATE'
                 order by column_name
                """,
                String.class);
        assertThat(updatable)
                .containsExactly(
                        "content_hash",
                        "content_length",
                        "failure",
                        "object_deleted_at",
                        "settled_at",
                        "status",
                        "upload_expires_at");
        assertThat(db.queryForObject(
                        "select has_table_privilege('app_rw', 'kernel.object_upload', 'DELETE')", Boolean.class))
                .isFalse();
    }

    /**
     * The sync quarantine (kernel V0084; CR-32-1 item 2): app_rw may write the four resolution
     * columns and nothing else, the raw event included (only kernel.sync_quarantine_drop_raw nulls
     * it), and never delete a row: a quarantined fact is resolved, never purged.
     */
    @Test
    void theQuarantineTakesOnlyItsResolutionColumns() {
        JdbcTemplate db = superuserJdbc();
        assertThat(problemsOf(db)).noneMatch(problem -> problem.startsWith("kernel.sync_quarantine:"));
        List<String> updatable = db.queryForList(
                """
                select column_name from information_schema.column_privileges
                 where table_schema = 'kernel' and table_name = 'sync_quarantine'
                   and grantee = 'app_rw' and privilege_type = 'UPDATE'
                 order by column_name
                """,
                String.class);
        assertThat(updatable).containsExactly("resolution", "resolution_reason", "resolved_at", "resolved_by_user_id");
        assertThat(db.queryForObject(
                        "select has_table_privilege('app_rw', 'kernel.sync_quarantine', 'DELETE')", Boolean.class))
                .isFalse();
    }

    /**
     * Wave 2 (RLS-13; {@code docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md} (1)):
     * a SECURITY DEFINER function whose search_path does not end in {@code pg_temp} searches the
     * caller's temporary schema first, so a temporary table could shadow a catalogue relation it
     * reads. Every definer function of the schemas fixed so far ends its search_path in pg_temp; the
     * schemas still to fix (customers, reporting) join this set with their wave 2 pull requests, and
     * the test PR of the plan (17) makes it every schema.
     */
    static final Set<String> DEFINER_SEARCH_PATH_FIXED = Set.of(
            "kernel",
            "hello",
            "party",
            "security",
            "catalogue",
            "pricing",
            "inventory",
            "trading",
            "pos",
            "integration");

    @Test
    void everyDefinerFunctionOfTheFixedSchemasEndsItsSearchPathInPgTemp() {
        List<String> unguarded = superuserJdbc()
                .queryForList(
                        """
                        select n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                               || ': search_path ' || coalesce((select c from unnest(p.proconfig) c
                                                                 where c like 'search_path=%'), 'not set')
                          from pg_proc p
                          join pg_namespace n on n.oid = p.pronamespace
                         where p.prosecdef
                           and n.nspname = any (?)
                           and not exists (select 1 from unnest(coalesce(p.proconfig, '{}'::text[])) c
                                            where c ~ '^search_path=.*[, ]pg_temp\\s*$')
                         order by 1
                        """,
                        String.class, (Object) DEFINER_SEARCH_PATH_FIXED.toArray(String[]::new));
        assertThat(unguarded)
                .as("SECURITY DEFINER functions whose search_path does not end in pg_temp")
                .isEmpty();
    }

    /** Proof that the rules bite: tables that break each of them are reported, by name. */
    @Test
    void aTableThatBreaksTheRulesIsReported() {
        JdbcTemplate db = superuserJdbc();
        try {
            db.execute("create table hello.zz_no_rls (id uuid primary key)");
            db.execute("grant select, insert on hello.zz_no_rls to app_rw");

            db.execute("create table hello.zz_not_forced (id uuid primary key)");
            db.execute("alter table hello.zz_not_forced enable row level security");
            db.execute("create policy p on hello.zz_not_forced for select to app_rw using (true)");

            db.execute("create table hello.zz_no_policy (id uuid primary key)");
            db.execute("alter table hello.zz_no_policy enable row level security");
            db.execute("alter table hello.zz_no_policy force row level security");

            db.execute("create table hello.zz_deletable (id uuid primary key)");
            db.execute("alter table hello.zz_deletable enable row level security");
            db.execute("alter table hello.zz_deletable force row level security");
            db.execute("create policy p on hello.zz_deletable for select to app_rw using (true)");
            db.execute("grant select, delete on hello.zz_deletable to app_rw");

            List<String> problems = problemsOf(db);

            assertThat(problems).anyMatch(p -> p.startsWith("hello.zz_no_rls: row-level security is not enabled"));
            assertThat(problems).anyMatch(p -> p.startsWith("hello.zz_not_forced: row-level security is not forced"));
            assertThat(problems).anyMatch(p -> p.startsWith("hello.zz_no_policy: no policy"));
            assertThat(problems).anyMatch(p -> p.startsWith("hello.zz_deletable: app_rw may DELETE"));
            // zz_no_rls is reported twice (not enabled, no policy); nothing else is wrong.
            assertThat(problems).hasSize(5);
        } finally {
            for (String table : List.of("zz_no_rls", "zz_not_forced", "zz_no_policy", "zz_deletable")) {
                db.execute("drop table if exists hello." + table);
            }
        }
    }

    /** Every violation in the migrated database, as sentences a developer can act on. */
    static List<String> problemsOf(JdbcTemplate db) {
        List<String> problems = new ArrayList<>();

        db.query(
                """
                select n.nspname || '.' || c.relname                      as name,
                       c.relrowsecurity                                   as enabled,
                       c.relforcerowsecurity                              as forced,
                       (select count(*) from pg_policy p where p.polrelid = c.oid) as policies,
                       has_table_privilege('app_rw', c.oid, 'DELETE')     as may_delete,
                       has_table_privilege('app_rw', c.oid, 'TRUNCATE')   as may_truncate
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where c.relkind in ('r', 'p')
                   and n.nspname !~ '^pg_' and n.nspname <> 'information_schema'
                   and c.relname <> 'flyway_schema_history'
                 order by 1
                """,
                row -> {
                    String table = row.getString("name");
                    if (!row.getBoolean("enabled")) {
                        problems.add(table + ": row-level security is not enabled; every tenant reads every row."
                                + " Add: ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY;");
                    } else if (!row.getBoolean("forced")) {
                        problems.add(table + ": row-level security is not forced, so the table owner bypasses it."
                                + " Add: ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY;");
                    }
                    if (row.getInt("policies") == 0) {
                        problems.add(table + ": no policy. Use the template of 17A section 6.3 (see the hello"
                                + " migration); for a table every tenant may read, say so:"
                                + " CREATE POLICY everyone_reads ON " + table + " FOR SELECT TO app_rw USING (true);");
                    }
                    if (row.getBoolean("may_delete") && !DELETE_BY_DESIGN.contains(table)) {
                        problems.add(table + ": app_rw may DELETE. Nothing is deleted in this system: a correction"
                                + " is a new, linked row (AGENTS.md). Remove the grant.");
                    }
                    if (row.getBoolean("may_truncate")) {
                        problems.add(table + ": app_rw may TRUNCATE. Remove the grant.");
                    }
                });

        db.query(
                """
                select nspname from pg_namespace
                 where nspname !~ '^pg_' and nspname <> 'information_schema'
                   and has_schema_privilege('app_rw', oid, 'CREATE')
                """,
                row -> {
                    problems.add("schema " + row.getString("nspname") + ": app_rw may CREATE objects in it;"
                            + " only the migration user creates tables.");
                });
        return problems;
    }
}
