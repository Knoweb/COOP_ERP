package lk.coopfed.knoweb.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The RLS matrix of 17A section 12, extended by 19A K-01 to the five classes (OWN, PARTY,
 * FEDERATION_VIEW, EXTERNAL_TIMEBOXED, NONE) and a transaction that forgot the scope, for
 * SELECT, INSERT, UPDATE and DELETE.
 *
 * <p>It runs twice over. Once against a table built from the SQL block of {@code
 * db/migration/RLS_POLICY_TEMPLATE.md}, read from the file, in a schema of its own: the
 * template must satisfy the matrix with no exception. Then against every real table of every
 * schema that has an {@code owner_entity_id} column, discovered from {@code pg_catalog}, with
 * the policies its migrations created: each must satisfy the same matrix, except where {@link
 * #EXCEPTIONS} says otherwise and why. A new table is covered the day its migration lands.
 *
 * <p>How: one superuser connection per table, in a transaction that is always rolled back, so
 * nothing is left behind and no other test sees the rows. Inside it, foreign keys and triggers
 * are off ({@code session_replication_role = replica}) and the table's check constraints are
 * dropped, because the fixture rows are made up and the rule under test is row-level security,
 * not the domain; the policies are untouched. Each check then runs in a savepoint as {@code
 * coop_app} (the application user, not a superuser, so the policies apply), with the four scope
 * settings {@code ScopeConnectionCustomizer} sets, and is rolled back to that savepoint.
 *
 * <p>Rows: entity A has one row at location 1 whose counterparty is B and, when the table has a
 * location column, one more row entity-wide (or at location 2 when a location is required);
 * entity B has one row at its own location whose counterparty is A; entity C has one row and
 * trades with nobody.
 */
class RlsMatrixIntegrationTest extends PostgresIntegrationTest {

    private static final String TEMPLATE_SCHEMA = "zz_rls_template";
    private static final String TEMPLATE_TABLE = TEMPLATE_SCHEMA + ".zz_rls_matrix";
    private static final String OWN_CLASS_TEST = "kernel.scope_class() = 'OWN'::text";

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID LOCATION_1 = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID LOCATION_2 = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID LOCATION_B = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    private static final UUID LOCATION_C = UUID.fromString("00000000-0000-0000-0000-000000000c01");

    private static final String VISIBLE = "visible";
    private static final String HIDDEN = "hidden";
    private static final String DONE = "done";
    private static final String REFUSED = "refused";

    // ---- the matrix -----------------------------------------------------------------------------

    enum Op {
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        MOVE
    }

    /** A scope, as the customizer would set it; {@code forgot} is a transaction with none. */
    record Scope(String name, String policyClass, UUID entity, UUID location, Set<UUID> granted, boolean forgot) {

        static Scope of(String name, String policyClass, UUID entity, UUID location, Set<UUID> granted) {
            return new Scope(name, policyClass, entity, location, granted, false);
        }

        boolean is(String candidate) {
            return !forgot && policyClass.equals(candidate);
        }
    }

    /** A fixture row, by what the policies look at. */
    record Row(String label, UUID owner, UUID location, UUID counterparty) {}

    /** One cell of the matrix: an operation, in a scope, on a row (none for INSERT). */
    record Check(Op op, Scope scope, String row) {
        String key() {
            return op + " as " + scope.name() + (row == null ? "" : " on " + row);
        }
    }

    /** What a table offers the application user, from pg_catalog. */
    record Shape(
            String table,
            boolean hasLocation,
            boolean locationRequired,
            String counterpartyColumn,
            Set<String> granted) {

        boolean hasCounterparty() {
            return counterpartyColumn != null;
        }
    }

    /**
     * A real table that departs from the template, how, and why. The function receives the check
     * and the template's expectation and returns this table's. Nothing is skipped: every check of
     * every table is still run and compared.
     */
    record Departure(String table, String why, Function<Check, String> expected, boolean mustExist) {
        Departure(String table, String why, Function<Check, String> expected) {
            this(table, why, expected, true);
        }
    }

    private static List<Scope> scopes(Shape shape) {
        List<Scope> scopes = new ArrayList<>();
        scopes.add(Scope.of("OWN(A)", "OWN", A, null, Set.of()));
        if (shape.hasLocation()) {
            scopes.add(Scope.of("OWN(A@1)", "OWN", A, LOCATION_1, Set.of()));
        }
        scopes.add(Scope.of("PARTY(B)", "PARTY", B, null, Set.of()));
        if (shape.hasLocation()) {
            scopes.add(Scope.of("PARTY(B@B)", "PARTY", B, LOCATION_B, Set.of()));
        }
        scopes.add(Scope.of("FEDERATION_VIEW(A)", "FEDERATION_VIEW", A, null, Set.of()));
        scopes.add(Scope.of("EXTERNAL(A,{C})", "EXTERNAL_TIMEBOXED", A, null, Set.of(C)));
        scopes.add(Scope.of("EXTERNAL(A,{B,C})", "EXTERNAL_TIMEBOXED", A, null, Set.of(B, C)));
        scopes.add(Scope.of("EXTERNAL(A,{})", "EXTERNAL_TIMEBOXED", A, null, Set.of()));
        // NONE with a real entity and a grant that names it: only the class test keeps it out.
        scopes.add(Scope.of("NONE(A,{A})", "NONE", A, null, Set.of(A)));
        scopes.add(new Scope("no scope", "", null, null, Set.of(), true));
        return scopes;
    }

    private static List<Row> rows(Shape shape) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("A@1 with B", A, LOCATION_1, B));
        if (shape.hasLocation()) {
            rows.add(new Row("A wide", A, shape.locationRequired() ? LOCATION_2 : null, null));
        }
        rows.add(new Row("B with A", B, LOCATION_B, A));
        rows.add(new Row("C alone", C, LOCATION_C, null));
        return rows;
    }

    /** The template's answer: what RLS_POLICY_TEMPLATE.md promises for this cell. */
    private static String template(Shape shape, Check check, Row row) {
        Scope scope = check.scope();
        return switch (check.op()) {
            case SELECT -> {
                if (!shape.granted().contains("SELECT")) {
                    yield REFUSED;
                }
                yield reads(shape, scope, row) ? VISIBLE : HIDDEN;
            }
            case INSERT -> shape.granted().contains("INSERT") && scope.is("OWN") ? DONE : REFUSED;
            case UPDATE, DELETE -> {
                if (!shape.granted().contains(check.op().name())) {
                    yield REFUSED;
                }
                yield scope.is("OWN") && ownRow(shape, scope, row) ? DONE : HIDDEN;
            }
            // OWN moving its own row to entity B: WITH CHECK refuses it.
            case MOVE -> REFUSED;
        };
    }

    private static boolean reads(Shape shape, Scope scope, Row row) {
        if (scope.forgot() || scope.is("NONE")) {
            return false;
        }
        if (scope.is("FEDERATION_VIEW")) {
            return true;
        }
        if (scope.is("EXTERNAL_TIMEBOXED")) {
            return scope.granted().contains(row.owner());
        }
        boolean own = ownRow(shape, scope, row);
        boolean party = shape.hasCounterparty() && (own || scope.entity().equals(row.counterparty()));
        if (scope.is("OWN")) {
            return own || party;
        }
        return scope.is("PARTY") && party;
    }

    /** own_read, own_update, own_delete: the owner, at the caller's location if it has one. */
    private static boolean ownRow(Shape shape, Scope scope, Row row) {
        return scope.entity().equals(row.owner())
                && (!shape.hasLocation()
                        || scope.location() == null
                        || scope.location().equals(row.location()));
    }

    // ---- the real tables that are not the template -----------------------------------------------

    /**
     * Where a real table is not the template, cell by cell. Two kinds: a deliberate departure
     * (reference data every class may read, a register the template's ext_view does not fit),
     * and a policy the matrix found wrong, which stays as it is until the owning module fixes
     * it in a migration of its own (merged migrations are never edited). Those carry a TODO
     * naming the ticket; when the fix lands, the exception goes and the template answer holds.
     */
    private static final List<Departure> EXCEPTIONS = List.of(
            // ---- deliberate -------------------------------------------------------------------
            new Departure(
                    "catalogue.batch",
                    "shared_read (M2-01): a batch is global identity, not owned; every class but NONE reads it",
                    RlsMatrixIntegrationTest::everyClassButNoneReadsEverything),
            new Departure(
                    "catalogue.batch_key",
                    "shared_read (m2catalogue V0003): the identity of a batch, read like the batch",
                    RlsMatrixIntegrationTest::everyClassButNoneReadsEverything),
            new Departure(
                    "catalogue.sku_uom_conversion",
                    "own_write (m2catalogue V0003) follows the parent SKU's owner; the matrix's made-up row has no SKU",
                    RlsMatrixIntegrationTest::childRowsFollowTheirParent),
            new Departure(
                    "catalogue.sku_barcode",
                    "own_write (m2catalogue V0003) follows the parent SKU's owner; the matrix's made-up row has no SKU",
                    RlsMatrixIntegrationTest::childRowsFollowTheirParent),
            new Departure(
                    "catalogue.sku_tag",
                    "own_write (m2catalogue V0003) follows the parent SKU's owner; the matrix's made-up row has no SKU",
                    RlsMatrixIntegrationTest::childRowsFollowTheirParent),
            new Departure(
                    "catalogue.tax_category",
                    "authenticated_read (M2-01): reference data every class but NONE reads",
                    RlsMatrixIntegrationTest::everyClassButNoneReadsEverything),
            new Departure(
                    "catalogue.tax_rate",
                    "authenticated_read (M2-01): reference data every class but NONE reads",
                    RlsMatrixIntegrationTest::everyClassButNoneReadsEverything),
            new Departure(
                    "security.app_user",
                    "no ext_view on purpose (m1security V0009): the row carries pin_hash and provider_subject,"
                            + " which RLS cannot hide by column; a regulator's view of staff waits for a masking view",
                    RlsMatrixIntegrationTest::externalReadsNothing),
            new Departure(
                    "security.external_grant",
                    "a grant is the Federation's row; the grantee reads its own through grantee_read (M1-09),"
                            + " not through ext_view on the entities it names",
                    RlsMatrixIntegrationTest::externalReadsNothing),
            new Departure(
                    "kernel.event_inbox",
                    "own_* only (kernel V0032): a consumer's claims are the worker's own bookkeeping in the"
                            + " entity's scope, not a register the federation or a grantee views",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            // ---- found by the matrix, to fix in the owning module ------------------------------
            // TODO(K-07 follow-up, CR-17A-3): party_read still applies the location line to both
            // sides, so a shop-scoped counterparty does not see the documents it is a side of.
            new Departure(
                    "kernel.document",
                    "party_read applies the location to the counterparty's side too (CR-17A-3 correction)",
                    check -> check.op() == Op.SELECT
                                    && check.scope().location() != null
                                    && (check.row().equals("B with A")
                                                    && check.scope().is("OWN")
                                            || check.row().equals("A@1 with B")
                                                    && check.scope().is("PARTY"))
                            ? HIDDEN
                            : null),
            // TODO(hello, the template module): ext_view waited for kernel.granted_entities()
            // (hello README); K-01 has landed, so hello can add it.
            new Departure("hello.greeting", "no ext_view yet", RlsMatrixIntegrationTest::externalReadsNothing),
            // The scaffolder's throwaway copy of hello (make test-scaffold: integration.webhook)
            // carries hello's policies, so it carries hello's departure; it exists only during
            // that proof, hence it is not required to exist.
            new Departure(
                    "integration.webhook",
                    "the scaffolded copy of hello.greeting: no ext_view yet",
                    RlsMatrixIntegrationTest::externalReadsNothing,
                    false),
            // TODO(M3 price list): pricing.price_list has no ext_view.
            new Departure("pricing.price_list", "no ext_view yet", RlsMatrixIntegrationTest::externalReadsNothing),
            // TODO(M1 party, after M1-09): party.entity has no ext_view; m1security V0009 left the
            // party schema to an m1party migration, and the other party tables have theirs.
            new Departure("party.entity", "no ext_view yet", RlsMatrixIntegrationTest::externalReadsNothing));

    private static String everyClassButNoneReadsEverything(Check check) {
        return check.op() == Op.SELECT
                        && !check.scope().forgot()
                        && !check.scope().is("NONE")
                ? VISIBLE
                : null;
    }

    /** An insert is admitted only when the caller owns the parent SKU; a made-up row has none. */
    private static String childRowsFollowTheirParent(Check check) {
        return check.op() == Op.INSERT && check.scope().is("OWN") ? REFUSED : null;
    }

    private static String onlyTheOwnerReads(Check check) {
        return check.op() == Op.SELECT
                        && (check.scope().is("EXTERNAL_TIMEBOXED")
                                || check.scope().is("FEDERATION_VIEW"))
                ? HIDDEN
                : null;
    }

    private static String externalReadsNothing(Check check) {
        return check.op() == Op.SELECT && check.scope().is("EXTERNAL_TIMEBOXED") ? HIDDEN : null;
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    void theTemplateFileSatisfiesTheMatrix() throws SQLException, IOException {
        String sql = templateSql().replace("<schema>.<table>", TEMPLATE_TABLE);
        try (Connection db = superuser()) {
            // Created in the matrix's own transaction, which is rolled back: nobody else ever
            // sees the schema, and a second run starts clean.
            db.setAutoCommit(false);
            try (Statement st = db.createStatement()) {
                st.execute("create schema " + TEMPLATE_SCHEMA);
                st.execute("grant usage on schema " + TEMPLATE_SCHEMA + " to app_rw");
                st.execute("create table " + TEMPLATE_TABLE + " (row_id uuid primary key default gen_random_uuid(),"
                        + " owner_entity_id uuid not null, counterparty_entity_id uuid, location_id uuid)");
                st.execute("grant select, insert, update, delete on " + TEMPLATE_TABLE + " to app_rw");
                st.execute(sql);
            }
            List<String> mismatches = runMatrix(db, TEMPLATE_TABLE, List.of());
            assertThat(mismatches).as("the template against the matrix").isEmpty();
        }
    }

    @Test
    void everyRealTableSatisfiesTheMatrixOrSaysWhyNot() throws SQLException {
        List<String> tables = ownedTables();
        assertThat(tables)
                .as("discovery found the owned tables")
                .contains("kernel.document", "party.location", "hello.greeting");
        List<String> known = tables.stream().toList();
        assertThat(EXCEPTIONS.stream().filter(Departure::mustExist))
                .as("every exception names a table that exists")
                .allSatisfy(e -> assertThat(known).contains(e.table()));

        List<String> mismatches = new ArrayList<>();
        for (String table : tables) {
            try (Connection db = superuser()) {
                List<Departure> own =
                        EXCEPTIONS.stream().filter(e -> e.table().equals(table)).toList();
                mismatches.addAll(runMatrix(db, table, own));
            }
        }
        assertThat(mismatches)
                .as("cells where a real table departs from the matrix and no exception says why")
                .isEmpty();
    }

    @Test
    void everyOwnedTableForcesRowLevelSecurityPartitionsIncluded() {
        List<String> notForced = superuserJdbc()
                .queryForList(
                        """
                        select n.nspname || '.' || c.relname
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace
                         where c.relkind in ('r', 'p')
                           and n.nspname not like 'pg\\_%' and n.nspname <> 'information_schema'
                           and exists (select 1 from pg_attribute a where a.attrelid = c.oid
                                        and a.attname = 'owner_entity_id' and not a.attisdropped)
                           and not (c.relrowsecurity and c.relforcerowsecurity)
                         order by 1
                        """,
                        String.class);
        assertThat(notForced)
                .as("owned tables without ENABLE and FORCE ROW LEVEL SECURITY")
                .isEmpty();
    }

    @Test
    void everyOwnPolicyTestsTheClassInEachOfItsClauses() {
        List<String> ungated = superuserJdbc()
                .queryForList(
                        """
                        select schemaname || '.' || tablename || '.' || policyname
                          from pg_policies
                         where policyname like 'own\\_%'
                           and ((qual is not null and position(? in qual) = 0)
                             or (with_check is not null and position(? in with_check) = 0))
                         order by 1
                        """,
                        String.class, OWN_CLASS_TEST, OWN_CLASS_TEST);
        assertThat(ungated)
                .as("own_* policies with a USING or WITH CHECK clause that does not test the class")
                .isEmpty();
    }

    // ---- the engine -----------------------------------------------------------------------------

    private List<String> runMatrix(Connection db, String table, List<Departure> exceptions) throws SQLException {
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            st.execute("set local session_replication_role = replica");
            for (String constraint : strings(
                    st,
                    "select conname from pg_constraint where contype = 'c' and conrelid = '" + table + "'::regclass")) {
                st.execute("alter table " + table + " drop constraint " + quote(constraint));
            }
            Columns columns = columns(st, table);
            Shape shape = columns.shape(table, granted(st, table));
            List<Scope> scopes = scopes(shape);
            List<Row> rows = rows(shape);

            Map<String, String> actual = new TreeMap<>();
            Map<String, String> expected = new TreeMap<>();

            // INSERT first, before the fixture rows exist, so a key made of the owner (party.entity)
            // cannot collide with them.
            for (Scope scope : scopes) {
                Check check = new Check(Op.INSERT, scope, null);
                expected.put(check.key(), expect(shape, check, null, exceptions));
                UUID owner = scope.entity() == null ? A : scope.entity();
                actual.put(
                        check.key(),
                        asApp(
                                st,
                                scope,
                                () -> st.executeUpdate(columns.insert(owner, scope.location(), null)) == 1
                                        ? DONE
                                        : HIDDEN));
            }
            Scope own = scopes.get(0);
            Check foreign = new Check(Op.INSERT, own, "a row of B");
            expected.put(foreign.key(), expect(shape, foreign, null, exceptions, REFUSED));
            actual.put(
                    foreign.key(),
                    asApp(st, own, () -> st.executeUpdate(columns.insert(B, null, null)) == 1 ? DONE : HIDDEN));

            Map<String, String> tids = new LinkedHashMap<>();
            for (Row row : rows) {
                try (ResultSet rs = st.executeQuery(columns.insert(row.owner(), row.location(), row.counterparty())
                        + " returning tableoid::regclass::text || '/' || ctid::text")) {
                    rs.next();
                    tids.put(row.label(), rs.getString(1));
                }
            }
            Map<String, String> labels =
                    tids.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

            for (Scope scope : scopes) {
                String read = asApp(
                        st,
                        scope,
                        () -> String.join(
                                ";",
                                strings(
                                        st,
                                        "select t.tableoid::regclass::text || '/' || t.ctid::text from " + table
                                                + " t")));
                for (Row row : rows) {
                    Check check = new Check(Op.SELECT, scope, row.label());
                    expected.put(check.key(), expect(shape, check, row, exceptions));
                    actual.put(
                            check.key(),
                            read.equals(REFUSED) || read.startsWith("error ")
                                    ? read
                                    : List.of(read.split(";")).contains(tids.get(row.label())) ? VISIBLE : HIDDEN);
                }
                for (Row row : rows) {
                    String where = where(tids.get(row.label()));
                    Check update = new Check(Op.UPDATE, scope, row.label());
                    expected.put(update.key(), expect(shape, update, row, exceptions));
                    actual.put(
                            update.key(),
                            asApp(
                                    st,
                                    scope,
                                    () -> st.executeUpdate("update " + table + " t set " + columns.touch() + " where "
                                                            + where)
                                                    == 1
                                            ? DONE
                                            : HIDDEN));
                    Check delete = new Check(Op.DELETE, scope, row.label());
                    expected.put(delete.key(), expect(shape, delete, row, exceptions));
                    actual.put(
                            delete.key(),
                            asApp(
                                    st,
                                    scope,
                                    () -> st.executeUpdate("delete from " + table + " t where " + where) == 1
                                            ? DONE
                                            : HIDDEN));
                }
            }
            Row mine = rows.get(0);
            Check move = new Check(Op.MOVE, own, mine.label());
            expected.put(move.key(), expect(shape, move, mine, exceptions));
            actual.put(
                    move.key(),
                    asApp(
                            st,
                            own,
                            () -> st.executeUpdate("update " + table + " t set " + columns.ownerSource() + " = '" + B
                                                    + "' where " + where(tids.get(mine.label())))
                                            == 1
                                    ? DONE
                                    : HIDDEN));

            List<String> mismatches = new ArrayList<>();
            expected.forEach((key, want) -> {
                String got = actual.get(key);
                if (!want.equals(got)) {
                    mismatches.add(table + ": " + key + ": expected " + want + ", got " + got);
                }
            });
            return mismatches;
        } finally {
            db.rollback();
        }
    }

    private static String expect(Shape shape, Check check, Row row, List<Departure> exceptions) {
        return expect(shape, check, row, exceptions, null);
    }

    private static String expect(Shape shape, Check check, Row row, List<Departure> exceptions, String fixed) {
        String answer = fixed != null ? fixed : template(shape, check, row);
        if (fixed != null && !shape.granted().contains("INSERT")) {
            answer = REFUSED;
        }
        for (Departure departure : exceptions) {
            String theirs = departure.expected().apply(check);
            if (theirs != null) {
                answer = theirs;
            }
        }
        return answer;
    }

    @FunctionalInterface
    private interface Work {
        String run() throws SQLException;
    }

    /** Runs the work as coop_app in the scope, in a savepoint that is always rolled back. */
    private static String asApp(Statement st, Scope scope, Work work) throws SQLException {
        st.execute("savepoint matrix_check");
        try {
            st.execute("set local role coop_app");
            if (!scope.forgot()) {
                st.execute("select set_config('app.scope_entity_id', '" + text(scope.entity()) + "', true),"
                        + " set_config('app.scope_location_id', '" + text(scope.location()) + "', true),"
                        + " set_config('app.scope_class', '" + scope.policyClass() + "', true),"
                        + " set_config('app.granted_entities', '{"
                        + scope.granted().stream().map(UUID::toString).collect(Collectors.joining(","))
                        + "}', true)");
            }
            return work.run();
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return REFUSED;
            }
            return "error " + e.getSQLState() + " " + e.getMessage();
        } finally {
            st.execute("rollback to savepoint matrix_check");
        }
    }

    private static String where(String tid) {
        int slash = tid.indexOf('/');
        return "t.tableoid = '" + tid.substring(0, slash) + "'::regclass and t.ctid = '" + tid.substring(slash + 1)
                + "'::tid";
    }

    // ---- the table's columns --------------------------------------------------------------------

    record Column(String name, String type, boolean notNull, boolean hasDefault, boolean generated, String expr) {}

    record Columns(String table, List<Column> list) {

        Column find(String name) {
            return list.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
        }

        /** The column that decides the owner: owner_entity_id, or what it is generated from. */
        String ownerSource() {
            Column owner = find("owner_entity_id");
            if (!owner.generated()) {
                return "owner_entity_id";
            }
            String source = owner.expr().replaceAll("[()]", "").trim();
            if (!source.matches("[a-z_]+")) {
                throw new IllegalStateException(table + ": owner_entity_id is generated from " + owner.expr());
            }
            return source;
        }

        String counterpartyColumn() {
            if (find("counterparty_entity_id") != null) {
                return "counterparty_entity_id";
            }
            // The relationship is the seller's; the buyer is the other side (party_read, M1-06).
            return table.equals("party.entity_relationship") ? "buyer_entity_id" : null;
        }

        Shape shape(String table, Set<String> granted) {
            Column location = find("location_id");
            return new Shape(
                    table, location != null, location != null && location.notNull(), counterpartyColumn(), granted);
        }

        /** A harmless assignment for UPDATE: the owner's source column to itself. */
        String touch() {
            return ownerSource() + " = t." + ownerSource();
        }

        String insert(UUID owner, UUID location, UUID counterparty) {
            Map<String, String> values = new LinkedHashMap<>();
            String cp = counterpartyColumn();
            for (Column column : list) {
                if (column.generated()) {
                    continue;
                }
                if (column.name().equals(ownerSource())) {
                    values.put(column.name(), literal(owner));
                } else if (column.name().equals("location_id")) {
                    UUID at = location == null && column.notNull() ? UUID.randomUUID() : location;
                    values.put(column.name(), at == null ? "null" : literal(at));
                } else if (column.name().equals(cp)) {
                    UUID other = counterparty == null && column.notNull() ? UUID.randomUUID() : counterparty;
                    values.put(column.name(), other == null ? "null" : literal(other));
                } else if (column.notNull() && !column.hasDefault()) {
                    values.put(column.name(), made(column.type()));
                }
            }
            return "insert into " + table + " (" + String.join(", ", values.keySet()) + ") values ("
                    + String.join(", ", values.values()) + ")";
        }
    }

    private static final Pattern SIZED = Pattern.compile("(character varying|character)\\((\\d+)\\)");

    /** A made-up value of the type: unique enough, and nothing more is asked of it. */
    private static String made(String type) {
        Matcher sized = SIZED.matcher(type);
        if (sized.matches()) {
            int length = Integer.parseInt(sized.group(2));
            return "rpad(md5(random()::text), " + length + ", 'x')::" + type;
        }
        if (type.endsWith("[]")) {
            return "'{}'::" + type;
        }
        if (type.startsWith("numeric")) {
            return "1";
        }
        return switch (type) {
            case "uuid" -> "gen_random_uuid()";
            case "text" -> "md5(random()::text)";
            case "smallint", "integer", "bigint" -> "(random() * 30000)::int";
            case "boolean" -> "false";
            case "date" -> "current_date";
            case "timestamp with time zone" -> "now()";
            case "timestamp without time zone" -> "localtimestamp";
            case "jsonb", "json" -> "'{}'::" + type;
            case "bytea" -> "'\\x00'::bytea";
            default -> throw new IllegalStateException("no made-up value for type " + type);
        };
    }

    private static Columns columns(Statement st, String table) throws SQLException {
        List<Column> list = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(
                """
                select a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, a.atthasdef,
                       a.attgenerated <> '' or a.attidentity <> '', pg_get_expr(d.adbin, d.adrelid)
                  from pg_attribute a
                  left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                 where a.attrelid = '%s'::regclass and a.attnum > 0 and not a.attisdropped
                 order by a.attnum
                """
                        .formatted(table))) {
            while (rs.next()) {
                list.add(new Column(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getBoolean(3),
                        rs.getBoolean(4),
                        rs.getBoolean(5),
                        rs.getString(6)));
            }
        }
        return new Columns(table, list);
    }

    private static Set<String> granted(Statement st, String table) throws SQLException {
        Set<String> granted = new java.util.HashSet<>();
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            if (strings(st, "select has_table_privilege('coop_app', '" + table + "'::regclass, '" + privilege + "')")
                    .get(0)
                    .equals("t")) {
                granted.add(privilege);
            }
        }
        return granted;
    }

    private static List<String> ownedTables() {
        return superuserJdbc()
                .queryForList(
                        """
                        select n.nspname || '.' || c.relname
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace
                         where c.relkind in ('r', 'p') and not c.relispartition
                           and n.nspname not like 'pg\\_%' and n.nspname <> 'information_schema'
                           and exists (select 1 from pg_attribute a where a.attrelid = c.oid
                                        and a.attname = 'owner_entity_id' and not a.attisdropped)
                         order by 1
                        """,
                        String.class);
    }

    private static List<String> strings(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String templateSql() throws IOException {
        try (InputStream in =
                RlsMatrixIntegrationTest.class.getResourceAsStream("/db/migration/RLS_POLICY_TEMPLATE.md")) {
            String markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher block = Pattern.compile("```sql\\n(.*?)```", Pattern.DOTALL).matcher(markdown.replace("\r", ""));
            assertThat(block.find())
                    .as("RLS_POLICY_TEMPLATE.md has a sql block")
                    .isTrue();
            return block.group(1);
        }
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String literal(UUID value) {
        return "'" + value + "'::uuid";
    }

    private static String text(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
