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
 * location column, one more row entity-wide (or at location 2 when a location is required),
 * addressed to location 1 where the table has a {@code to_location_id} or {@code
 * from_location_id}; entity B has one row at its own location whose counterparty is A and, when
 * the table has a location column, one more at its second location (so a PARTY session at B's
 * first location is held to it, RLS-11); entity C has one row and trades with nobody. Scopes on
 * a table with a location add OWN and PARTY at a location.
 *
 * <p>The tables keyed on a document rather than an owner (the rows of a document: lines, links,
 * history, attachments, every module's extension table) run a matrix of their own against
 * made-up {@code kernel.document} headers: {@link #everyRowOfADocumentFollowsItsHeader()}.
 *
 * <p>On a table with a location, three more cells (PLAN_TO_M2 6.12): OWN at location 1 cannot
 * insert at location 2 of its own entity, nor with no location, nor move its row to location 2;
 * the entity-wide OWN session inserts at location 2.
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
    private static final UUID LOCATION_B2 = UUID.fromString("00000000-0000-0000-0000-000000000b02");
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
        MOVE,
        /** An insert at location 2, in a scope at location 1 or entity-wide (PLAN_TO_M2 6.12). */
        INSERT_AT_ANOTHER_LOCATION,
        /** An insert with no location, in a scope at location 1. */
        INSERT_WITHOUT_LOCATION,
        /** The row at location 1 moved to location 2, in a scope at location 1. */
        MOVE_TO_ANOTHER_LOCATION
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

    /**
     * A fixture row, by what the policies look at. {@code addressedTo} fills a {@code
     * to_location_id} or {@code from_location_id} column (the shop a transfer or a request is
     * addressed to, which dest_read and source_read admit); null leaves it made up.
     */
    record Row(String label, UUID owner, UUID location, UUID counterparty, UUID addressedTo) {
        Row(String label, UUID owner, UUID location, UUID counterparty) {
            this(label, owner, location, counterparty, null);
        }
    }

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
            // PARTY at a location (RLS-11, RLS-17 d): its own entity's rows at its location only,
            // and every row it is the counterparty of. B has two locations, so the owner-side
            // location line is exercised; A's shop reads as PARTY too.
            scopes.add(Scope.of("PARTY(B@B)", "PARTY", B, LOCATION_B, Set.of()));
            scopes.add(Scope.of("PARTY(A@1)", "PARTY", A, LOCATION_1, Set.of()));
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
            // Addressed to location 1: what a shop at location 1 reads of a transfer sent to it
            // (dest_read) or a request asking it (source_read), on the tables that have one.
            rows.add(new Row("A wide", A, shape.locationRequired() ? LOCATION_2 : null, null, LOCATION_1));
        }
        rows.add(new Row("B with A", B, LOCATION_B, A));
        if (shape.hasLocation()) {
            rows.add(new Row("B@B2 with A", B, LOCATION_B2, A));
        }
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
            // A shop writes only at its own location (doc 18 section 3.7, M-05; PLAN_TO_M2 6.12):
            // own_write and own_update's WITH CHECK carry own_read's location line. An
            // entity-wide session writes at any location of its entity.
            case INSERT_AT_ANOTHER_LOCATION ->
                shape.granted().contains("INSERT") && scope.is("OWN") && scope.location() == null ? DONE : REFUSED;
            case INSERT_WITHOUT_LOCATION, MOVE_TO_ANOTHER_LOCATION -> REFUSED;
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
                    "catalogue.sku_image",
                    "own_write (m2catalogue V0005) follows the SKU (its owner, or SHARED for a local override);"
                            + " the matrix's made-up row has no SKU",
                    RlsMatrixIntegrationTest::childRowsFollowTheirParent),
            new Departure(
                    "catalogue.sku_tag",
                    "own_write (m2catalogue V0003) follows the parent SKU's owner; the matrix's made-up row has no SKU",
                    RlsMatrixIntegrationTest::childRowsFollowTheirParent),
            // catalogue.supplier follows the template since m2catalogue V0007: beyond it, a supplier
            // is read by everyone once a batch cites it (cited_read), which no made-up row is.
            new Departure(
                    "pricing.control_price",
                    "the Federation's rows only (m3pricing V0006; wave 2, RLS-04): a gazetted ceiling is law"
                            + " every class but NONE reads (23A section 3), and only the Federation writes one;"
                            + " the matrix's made-up rows belong to A, B and C, so nobody reads them and nobody"
                            + " inserts one (RetailPricingPostgresIntegrationTest reads the Federation's row)",
                    RlsMatrixIntegrationTest::federationRowsOnly),
            new Departure(
                    "pricing.mrp_policy",
                    "everyone_reads (m3pricing V0005): a society's effective policy falls back to the"
                            + " Federation's row for the item (EffectivePolicy)",
                    RlsMatrixIntegrationTest::everyClassButNoneReadsEverything),
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
            new Departure(
                    "kernel.notification_pending",
                    "own_* only (kernel V0059, V0063): what a retry of a queued notification needs, sealed, held until it"
                            + " is settled; the sweep reads it in the owner's scope, nobody else at all",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            // The ENTITY series is the whole entity's: a shop-scoped session reads and advances it,
            // because a document at a shop may draw its number from an ENTITY series (24B) and a
            // counter carries no business content (kernel V0055; decided 27 September 2026 on the
            // architect's delegation). own_read and own_update admit location_id IS NULL there.
            // kernel.document does not (kernel V0061): a shop reads its own documents only.
            new Departure(
                    "kernel.numbering_series",
                    "own_read, own_update and own_write admit a NULL location (the ENTITY series) at a location scope",
                    RlsMatrixIntegrationTest::entityWideRowsAtALocation),
            new Departure(
                    "integration.notification_template",
                    "everyone_reads (m9integration V0001): no personal data; the kernel's renderer reads a"
                            + " template after the commit with no scope at all",
                    RlsMatrixIntegrationTest::everySessionReadsEverything),
            new Departure(
                    "integration.notification_rule",
                    "everyone_reads (m9integration V0001): no personal data; the kernel's dispatcher reads the"
                            + " rules in the scope of whichever entity's event it matches",
                    RlsMatrixIntegrationTest::everySessionReadsEverything),
            // Member identity is the society's (CR-18-2, m7customers V0003; wave 2, RLS-01, RLS-02):
            // FEDERATION_VIEW and EXTERNAL_TIMEBOXED read no personal data of a natural person by
            // policy. The credit book (customer_account, the postings, allocations, history,
            // adjustments, the CPRs) keeps the template: money, no name.
            new Departure(
                    "customers.customer",
                    "no fed_view or ext_view (m7customers V0003, CR-18-2): a member's name, NIC hash and status are the"
                            + " society's; identity reaches the Federation only through an audited query or export",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            new Departure(
                    "customers.customer_phone",
                    "no fed_view or ext_view (m7customers V0003, CR-18-2): a member's phone numbers are the society's",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            new Departure(
                    "customers.customer_consent",
                    "no fed_view or ext_view (m7customers V0003, CR-18-2): a member's consents are the society's",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            new Departure(
                    "customers.data_subject_request",
                    "no fed_view or ext_view (m7customers V0003, CR-18-2): its notes and outcome are free text about"
                            + " the person, redacted at an erasure and read by the society alone until then",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            new Departure(
                    "integration.notification_contact",
                    "own_* only (m9integration V0003, fed_view dropped by V0006; wave 2, M9-06 and"
                            + " 2026-10-06-wave2-member-identity-visibility.md (1)): an address is personal data"
                            + " that neither the Federation's view nor a regulator's reads; the dispatcher reaches"
                            + " it through notification_recipients(), which answers an OWN caller for the"
                            + " entities it trades with",
                    RlsMatrixIntegrationTest::onlyTheOwnerReads),
            // A shop reads what is addressed to it at another shop of its entity (wave 2, RLS-17 d):
            // the fixture's "A wide" row is at location 2 and addressed to location 1.
            new Departure(
                    "inventory.transfer",
                    "dest_read (m5inventory V0004): the receiving shop reads a transfer sent to it",
                    RlsMatrixIntegrationTest::addressedToTheShop),
            new Departure(
                    "inventory.transfer_line",
                    "dest_read (m5inventory V0004): the receiving shop reads the lines of a transfer sent to it",
                    RlsMatrixIntegrationTest::addressedToTheShop),
            new Departure(
                    "inventory.transfer_receipt",
                    "source_read (m5inventory V0004): the sending shop reads the receipt of its transfer",
                    RlsMatrixIntegrationTest::addressedToTheShop),
            new Departure(
                    "trading.transfer_request",
                    "source_read (m4trading V0008): the stores read what is asked of them",
                    RlsMatrixIntegrationTest::addressedToTheShop),
            new Departure(
                    "trading.transfer_request_line",
                    "source_read (m4trading V0008): the stores read the lines asked of them",
                    RlsMatrixIntegrationTest::addressedToTheShop),
            new Departure(
                    "trading.transfer_request_decision",
                    "dest_read (m4trading V0008): the asking shop reads the decision on its request",
                    RlsMatrixIntegrationTest::addressedToTheShop),
            // ---- found by the matrix, to fix in the owning module ------------------------------
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
                    false)
            // pricing.price_list and price_list_line follow the template since m3pricing V0003
            // (M3-03); beyond it, buyer_read admits the buyer of a relationship that binds the list,
            // which no made-up row is. party.entity has its ext_view since m1party V0010.
            );

    private static String everyClassButNoneReadsEverything(Check check) {
        return check.op() == Op.SELECT
                        && !check.scope().forgot()
                        && !check.scope().is("NONE")
                ? VISIBLE
                : null;
    }

    /**
     * Rows the Federation alone writes and everyone reads (the fed_admin form of CR-21A-1 item 2 on
     * {@code kernel.system_entity()}): a made-up row owned by another entity is hidden to every
     * class and an insert of one is refused. UPDATE, DELETE and MOVE keep the template's answer
     * (the table grants UPDATE on one column and no DELETE, so both are refused before any policy).
     */
    private static String federationRowsOnly(Check check) {
        return switch (check.op()) {
            case SELECT -> HIDDEN;
            case INSERT -> REFUSED;
            default -> null;
        };
    }

    /** Reference data read by every session, scope or none (the notification templates and rules). */
    private static String everySessionReadsEverything(Check check) {
        return check.op() == Op.SELECT ? VISIBLE : null;
    }

    /** An insert is admitted only when the caller owns the parent SKU; a made-up row has none. */
    private static String childRowsFollowTheirParent(Check check) {
        return check.op() == Op.INSERT && check.scope().is("OWN") ? REFUSED : null;
    }

    /**
     * "A wide" (the owner's row with no location) is visible to OWN(A@1). own_update admits it
     * too, but the matrix's UPDATE touches owner_entity_id, which these tables grant to nobody,
     * so that cell stays the template's REFUSED.
     */
    private static String entityWideRowsAtALocation(Check check) {
        // A shop creates the ENTITY series its document draws from (kernel V0064).
        if (check.op() == Op.INSERT_WITHOUT_LOCATION) {
            return DONE;
        }
        return check.op() == Op.SELECT
                        && check.scope().is("OWN")
                        && check.scope().location() != null
                        && "A wide".equals(check.row())
                ? VISIBLE
                : null;
    }

    private static String onlyTheOwnerReads(Check check) {
        return check.op() == Op.SELECT
                        && (check.scope().is("EXTERNAL_TIMEBOXED")
                                || check.scope().is("FEDERATION_VIEW"))
                ? HIDDEN
                : null;
    }

    /** OWN at location 1 reads the owner's row at location 2 that is addressed to location 1. */
    private static String addressedToTheShop(Check check) {
        return check.op() == Op.SELECT
                        && check.scope().is("OWN")
                        && LOCATION_1.equals(check.scope().location())
                        && "A wide".equals(check.row())
                ? VISIBLE
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
        List<String> tables =
                ownedTables().stream().filter(Keyed::owned).map(Keyed::table).toList();
        assertThat(tables)
                .as("discovery found the owned tables")
                .contains("kernel.document", "party.location", "hello.greeting", "kernel.object_upload");
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

    // ---- the rows of a document -----------------------------------------------------------------

    /**
     * The rows of a document whose write policy does not refuse a row under a header issued in an
     * earlier transaction, and why (RLS_POLICY_TEMPLATE.md, "The rows of a document";
     * {@code 2026-10-06-wave2-extension-rows-after-issue.md} (1), (2)). Every other one does.
     */
    private static final Map<String, String> WRITTEN_AFTER_ISSUE = Map.of(
            "kernel.document_attachment",
            "attachments may be added to an issued document (the template's rule, decision (1))",
            "kernel.document_state_history",
            "a status change after issue (a cancel, a settlement) is recorded against the issued document",
            "kernel.document_link",
            "a correcting document links the issued original; a kernel row, not a module's extension table",
            "trading.claim_photo",
            "photographs may be added to an issued claim, as attachments (decision (1))",
            "trading.payment_allocation",
            "an insert-only fact about an issued receipt, not an extension row (decision (2))",
            "trading.cheque",
            "an insert-only fact about an issued receipt, not an extension row (decision (2))");

    /**
     * The rows of a document written before issue only, not even in the issuing transaction:
     * kernel V0055 holds {@code kernel.document_line} to {@code kernel.document_unissued}.
     */
    private static final Set<String> WRITTEN_BEFORE_ISSUE_ONLY = Set.of("kernel.document_line");

    /**
     * RLS-17 (a), (f): the tables keyed on a document rather than an owner (kernel.document_line,
     * link, history, attachment, and every module's extension table) follow their header, read
     * and write, under every class. Each runs against made-up kernel.document headers, one per
     * fixture row of the matrix (OWN at the header's location, PARTY as counterparty, PARTY at a
     * location, FEDERATION_VIEW, EXTERNAL by grant, NONE, no scope): a row is visible exactly when
     * its header is, under kernel.document's own policies (which follow the template), and the
     * owner inserts one, in an OWN scope at the header's location or entity-wide, under an
     * unissued header. Then {@code kernel.document_open_for_write} (kernel V0086): an insert under
     * a header issued in this transaction passes, under one issued in an earlier (committed)
     * transaction is refused, except where {@link #WRITTEN_AFTER_ISSUE} says why not.
     */
    @Test
    void everyRowOfADocumentFollowsItsHeader() throws SQLException {
        List<Keyed> tables =
                ownedTables().stream().filter(keyed -> !keyed.owned()).toList();
        List<String> names = tables.stream().map(Keyed::table).toList();
        assertThat(names)
                .as("discovery found the rows of a document")
                .contains(
                        "kernel.document_line",
                        "kernel.document_link",
                        "kernel.document_attachment",
                        "trading.doc_order",
                        "trading.doc_grn_line",
                        "trading.claim_photo",
                        "trading.payment_allocation");
        assertThat(WRITTEN_AFTER_ISSUE.keySet())
                .as("every table written after issue is a row of a document")
                .allSatisfy(table -> assertThat(names).contains(table));
        assertThat(WRITTEN_BEFORE_ISSUE_ONLY)
                .as("every table written before issue only is a row of a document")
                .allSatisfy(table -> assertThat(names).contains(table));

        // Issued in an earlier transaction: committed, so its xmin is not the matrix's
        // transaction, and removed afterwards.
        UUID earlier = UUID.randomUUID();
        try (Connection db = superuser()) {
            db.setAutoCommit(false);
            try (Statement st = db.createStatement()) {
                st.execute("set local session_replication_role = replica");
                st.execute(header(earlier, new Row("issued earlier", A, LOCATION_1, B), true));
            }
            db.commit();
        }
        List<String> mismatches = new ArrayList<>();
        try {
            for (Keyed keyed : tables) {
                try (Connection db = superuser()) {
                    mismatches.addAll(runDocumentMatrix(db, keyed, earlier));
                }
            }
        } finally {
            try (Connection db = superuser()) {
                db.setAutoCommit(false);
                try (Statement st = db.createStatement()) {
                    st.execute("set local session_replication_role = replica");
                    st.execute("delete from kernel.document where document_id = '" + earlier + "'");
                }
                db.commit();
            }
        }
        assertThat(mismatches)
                .as("cells where a row of a document does not follow its header")
                .isEmpty();
    }

    /** kernel.document as the header's policies see it: a location and a counterparty. */
    private static final Shape HEADER =
            new Shape("kernel.document", true, false, "counterparty_entity_id", Set.of("SELECT", "INSERT", "UPDATE"));

    private List<String> runDocumentMatrix(Connection db, Keyed keyed, UUID earlier) throws SQLException {
        String table = keyed.table();
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            st.execute("set local session_replication_role = replica");
            for (String constraint : strings(
                    st,
                    "select conname from pg_constraint where contype = 'c' and conrelid = '" + table + "'::regclass")) {
                st.execute("alter table " + table + " drop constraint " + quote(constraint));
            }
            Columns columns = columns(st, table);
            Set<String> granted = granted(st, table);
            List<Scope> scopes = scopes(HEADER);
            List<Row> rows = rows(HEADER);

            Map<String, UUID> headers = new LinkedHashMap<>();
            for (Row row : rows) {
                UUID id = UUID.randomUUID();
                headers.put(row.label(), id);
                st.execute(header(id, row, false));
            }
            // Issued in this transaction: the header's xmin is the current transaction's id.
            UUID now = UUID.randomUUID();
            st.execute(header(now, new Row("issued now", A, LOCATION_1, B), true));

            Map<String, String> actual = new TreeMap<>();
            Map<String, String> expected = new TreeMap<>();

            // INSERT first, before any row exists, so a key made of the document alone (one
            // extension row per document) cannot collide with the fixture.
            for (Scope scope : scopes) {
                for (Row row : rows) {
                    Check check = new Check(Op.INSERT, scope, "under " + row.label());
                    expected.put(
                            check.key(),
                            granted.contains("INSERT") && scope.is("OWN") && ownRow(HEADER, scope, row)
                                    ? DONE
                                    : REFUSED);
                    String sql = columns.insertUnder(keyed.documentKeys(), headers.get(row.label()));
                    actual.put(check.key(), asApp(st, scope, () -> st.executeUpdate(sql) == 1 ? DONE : HIDDEN));
                }
            }
            Scope owner = scopes.get(0);
            Check issuedNow = new Check(Op.INSERT, owner, "under A@1 issued in this transaction");
            expected.put(
                    issuedNow.key(),
                    granted.contains("INSERT") && !WRITTEN_BEFORE_ISSUE_ONLY.contains(table) ? DONE : REFUSED);
            actual.put(
                    issuedNow.key(),
                    asApp(
                            st,
                            owner,
                            () -> st.executeUpdate(columns.insertUnder(keyed.documentKeys(), now)) == 1
                                    ? DONE
                                    : HIDDEN));
            Check issuedEarlier = new Check(Op.INSERT, owner, "under A@1 issued in an earlier transaction");
            expected.put(
                    issuedEarlier.key(),
                    granted.contains("INSERT") && WRITTEN_AFTER_ISSUE.containsKey(table) ? DONE : REFUSED);
            actual.put(
                    issuedEarlier.key(),
                    asApp(
                            st,
                            owner,
                            () -> st.executeUpdate(columns.insertUnder(keyed.documentKeys(), earlier)) == 1
                                    ? DONE
                                    : HIDDEN));

            Map<String, String> tids = new LinkedHashMap<>();
            for (Row row : rows) {
                try (ResultSet rs = st.executeQuery(columns.insertUnder(keyed.documentKeys(), headers.get(row.label()))
                        + " returning tableoid::regclass::text || '/' || ctid::text")) {
                    rs.next();
                    tids.put(row.label(), rs.getString(1));
                }
            }
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
                    Check check = new Check(Op.SELECT, scope, "under " + row.label());
                    expected.put(
                            check.key(),
                            !granted.contains("SELECT") ? REFUSED : reads(HEADER, scope, row) ? VISIBLE : HIDDEN);
                    actual.put(
                            check.key(),
                            read.equals(REFUSED) || read.startsWith("error ")
                                    ? read
                                    : List.of(read.split(";")).contains(tids.get(row.label())) ? VISIBLE : HIDDEN);
                }
            }

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

    /** A made-up kernel.document header for the fixture row, unissued or issued. */
    private static String header(UUID id, Row row, boolean issued) {
        String columns = "document_id, doc_type_code, owner_entity_id, counterparty_entity_id, location_id, status";
        String values = literal(id) + ", 'ZZ', " + literal(row.owner()) + ", "
                + (row.counterparty() == null ? "null" : literal(row.counterparty())) + ", "
                + (row.location() == null ? "null" : literal(row.location())) + ", "
                + (issued ? "'ISSUED'" : "'DRAFT'");
        if (issued) {
            columns += ", series_id, doc_number, doc_number_display, issued_at, business_date, content_hash";
            values += ", gen_random_uuid(), 1, 'ZZ-1', now(), date '2026-01-01', rpad('0', 64, '0')";
        }
        return "insert into kernel.document (" + columns + ") values (" + values + ")";
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

    @Test
    void invoiceMaskingViewsHideSensitiveColumnsFromCounterparty() throws SQLException {
        try (Connection db = superuser()) {
            db.setAutoCommit(false);
            try (Statement st = db.createStatement()) {
                st.execute("set local session_replication_role = replica");

                UUID docId = UUID.randomUUID();
                st.execute(
                        "insert into kernel.document (document_id, doc_type_code, owner_entity_id, counterparty_entity_id, notes, status) values ('"
                                + docId + "', 'INVOICE', '" + A + "', '" + B + "', 'secret note', 'DRAFT')");
                st.execute(
                        "insert into kernel.document_line (document_line_id, document_id, line_no, sku_id, qty, unit_price, unit_cost_at_issue) values (gen_random_uuid(), '"
                                + docId + "', 1, gen_random_uuid(), 1, 100, 50)");
                st.execute(
                        "insert into trading.doc_invoice (document_id, relationship_id, seller_entity_id, buyer_entity_id, grn_document_ids, seller_vat_no, buyer_vat_no, tax_point_date, due_date) values ('"
                                + docId + "', gen_random_uuid(), '" + A + "', '" + B
                                + "', '{}', 'V1', 'V2', current_date, current_date)");

                Columns partyCols = columns(st, "trading.v_invoice_party");
                assertThat(partyCols.find("notes")).isNull();
                Columns linePartyCols = columns(st, "trading.v_invoice_line_party");
                assertThat(linePartyCols.find("unit_cost_at_issue")).isNull();

                String visibleToB = asApp(st, Scope.of("PARTY", "PARTY", B, null, Set.of()), () -> {
                    try (ResultSet rs = st.executeQuery(
                            "select count(*) from trading.v_invoice_party where document_id = '" + docId + "'")) {
                        rs.next();
                        return rs.getInt(1) == 1 ? VISIBLE : HIDDEN;
                    }
                });
                assertThat(visibleToB).isEqualTo(VISIBLE);

                String visibleLineToB = asApp(st, Scope.of("PARTY", "PARTY", B, null, Set.of()), () -> {
                    try (ResultSet rs = st.executeQuery(
                            "select count(*) from trading.v_invoice_line_party where document_id = '" + docId + "'")) {
                        rs.next();
                        return rs.getInt(1) == 1 ? VISIBLE : HIDDEN;
                    }
                });
                assertThat(visibleLineToB).isEqualTo(VISIBLE);

                String visibleToC = asApp(st, Scope.of("PARTY", "PARTY", UUID.randomUUID(), null, Set.of()), () -> {
                    try (ResultSet rs = st.executeQuery(
                            "select count(*) from trading.v_invoice_party where document_id = '" + docId + "'")) {
                        rs.next();
                        return rs.getInt(1) == 1 ? VISIBLE : HIDDEN;
                    }
                });
                assertThat(visibleToC).isEqualTo(HIDDEN);

                String visibleToA = asApp(st, Scope.of("OWN", "OWN", A, null, Set.of()), () -> {
                    try (ResultSet rs = st.executeQuery(
                            "select count(*) from trading.v_invoice_party where document_id = '" + docId + "'")) {
                        rs.next();
                        return rs.getInt(1) == 1 ? VISIBLE : HIDDEN;
                    }
                });
                assertThat(visibleToA).isEqualTo(VISIBLE);

                String visibleBaseToA = asApp(st, Scope.of("OWN", "OWN", A, null, Set.of()), () -> {
                    try (ResultSet rs = st.executeQuery("select count(*) from kernel.document where document_id = '"
                            + docId + "' and notes = 'secret note'")) {
                        rs.next();
                        return rs.getInt(1) == 1 ? VISIBLE : HIDDEN;
                    }
                });
                assertThat(visibleBaseToA).isEqualTo(VISIBLE);

            } finally {
                db.rollback();
            }
        }
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

            // A shop at location 1 inserts at location 2 of its own entity, or with no location;
            // the entity-wide session inserts at location 2. Only on a table with a location.
            if (shape.hasLocation()) {
                Scope atShop = scopes.stream()
                        .filter(s -> "OWN(A@1)".equals(s.name()))
                        .findFirst()
                        .orElseThrow();
                for (Scope scope : List.of(own, atShop)) {
                    Check elsewhere = new Check(Op.INSERT_AT_ANOTHER_LOCATION, scope, "at location 2");
                    expected.put(elsewhere.key(), expect(shape, elsewhere, null, exceptions));
                    actual.put(
                            elsewhere.key(),
                            asApp(
                                    st,
                                    scope,
                                    () -> st.executeUpdate(columns.insert(A, LOCATION_2, null)) == 1 ? DONE : HIDDEN));
                }
                if (!shape.locationRequired()) {
                    Check nowhere = new Check(Op.INSERT_WITHOUT_LOCATION, atShop, "with no location");
                    expected.put(nowhere.key(), expect(shape, nowhere, null, exceptions));
                    actual.put(
                            nowhere.key(),
                            asApp(
                                    st,
                                    atShop,
                                    () -> st.executeUpdate(columns.insert(A, null, null)) == 1 ? DONE : HIDDEN));
                }
            }

            Map<String, String> tids = new LinkedHashMap<>();
            for (Row row : rows) {
                try (ResultSet rs = st.executeQuery(
                        columns.insert(row.owner(), row.location(), row.counterparty(), row.addressedTo())
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
            if (shape.hasLocation()) {
                Scope atShop = scopes.stream()
                        .filter(s -> "OWN(A@1)".equals(s.name()))
                        .findFirst()
                        .orElseThrow();
                Check relocate = new Check(Op.MOVE_TO_ANOTHER_LOCATION, atShop, mine.label());
                expected.put(relocate.key(), expect(shape, relocate, mine, exceptions));
                actual.put(
                        relocate.key(),
                        asApp(
                                st,
                                atShop,
                                () -> st.executeUpdate("update " + table + " t set location_id = '" + LOCATION_2
                                                        + "' where " + where(tids.get(mine.label())))
                                                == 1
                                        ? DONE
                                        : HIDDEN));
            }

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

    /** The columns that address a row to another shop of the owner (dest_read, source_read). */
    static final Set<String> ADDRESSED = Set.of("to_location_id", "from_location_id");

    record Columns(String table, List<Column> list) {

        /**
         * A row of a document (no owner column): every column in {@code keys} names the made-up
         * header, every other required column a made-up value.
         */
        String insertUnder(Set<String> keys, UUID header) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Column column : list) {
                if (column.generated()) {
                    continue;
                }
                if (keys.contains(column.name())) {
                    values.put(column.name(), literal(header));
                } else if (column.notNull() && !column.hasDefault()) {
                    values.put(column.name(), made(column.type()));
                }
            }
            return "insert into " + table + " (" + String.join(", ", values.keySet()) + ") values ("
                    + String.join(", ", values.values()) + ")";
        }

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
            return insert(owner, location, counterparty, null);
        }

        String insert(UUID owner, UUID location, UUID counterparty, UUID addressedTo) {
            Map<String, String> values = new LinkedHashMap<>();
            String cp = counterpartyColumn();
            for (Column column : list) {
                if (column.generated()) {
                    continue;
                }
                if (column.name().equals(ownerSource())) {
                    values.put(column.name(), literal(owner));
                } else if (addressedTo != null && ADDRESSED.contains(column.name())) {
                    values.put(column.name(), literal(addressedTo));
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

    /**
     * A table the matrices cover: keyed on its owner ({@code owner_entity_id}), or on the document
     * whose rows it holds ({@code documentKeys}: the columns its policies hand to {@code
     * kernel.document_visible} or {@code kernel.document_owned}, else {@code document_id}).
     */
    record Keyed(String table, Set<String> documentKeys) {
        boolean owned() {
            return documentKeys.isEmpty();
        }
    }

    private static final Pattern DOCUMENT_KEY = Pattern.compile("kernel\\.document_(?:visible|owned)\\((\\w+)\\)");

    /**
     * Every parent table of every schema keyed on {@code owner_entity_id} or on a document: a
     * {@code document_id} column with no owner of its own, or a policy that asks the header
     * (RLS-17 a). A new table of either kind is covered the day its migration lands.
     */
    private static List<Keyed> ownedTables() {
        List<Keyed> keyed = new ArrayList<>();
        superuserJdbc()
                .query(
                        """
                        select n.nspname || '.' || c.relname as name,
                               exists (select 1 from pg_attribute a where a.attrelid = c.oid
                                        and a.attname = 'owner_entity_id' and not a.attisdropped) as owned,
                               coalesce((select string_agg(coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ' '
                                                           || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), ''), ' ')
                                           from pg_policy p where p.polrelid = c.oid), '') as policies
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace
                         where c.relkind in ('r', 'p') and not c.relispartition
                           and n.nspname not like 'pg\\_%' and n.nspname <> 'information_schema'
                           and (exists (select 1 from pg_attribute a where a.attrelid = c.oid
                                         and a.attname in ('owner_entity_id', 'document_id') and not a.attisdropped)
                                or exists (select 1 from pg_policy p where p.polrelid = c.oid
                                            and coalesce(pg_get_expr(p.polqual, p.polrelid), '')
                                                || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
                                                like '%kernel.document\\_visible(%'))
                         order by 1
                        """,
                        row -> {
                            if (row.getBoolean("owned")) {
                                keyed.add(new Keyed(row.getString("name"), Set.of()));
                                return;
                            }
                            Set<String> keys = new java.util.TreeSet<>();
                            Matcher key = DOCUMENT_KEY.matcher(row.getString("policies"));
                            while (key.find()) {
                                keys.add(key.group(1));
                            }
                            keyed.add(new Keyed(row.getString("name"), keys.isEmpty() ? Set.of("document_id") : keys));
                        });
        return keyed;
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
