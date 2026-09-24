package lk.coopfed.knoweb.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The RLS matrix of 17A section 12, extended by 19A K-01 to the five classes: OWN, PARTY,
 * FEDERATION_VIEW, EXTERNAL_TIMEBOXED, NONE, and a transaction that forgot the scope. It runs
 * against a throwaway table that carries the template of {@code db/migration/RLS_POLICY_TEMPLATE.md}
 * word for word, as the application user, so it proves the template and not a module.
 *
 * <p>Rows: entity A has one row at location 1 whose counterparty is B, and one entity-wide row
 * with no counterparty; entity B has one row whose counterparty is A; entity C has one row and
 * trades with nobody.
 */
class RlsMatrixIntegrationTest extends PostgresIntegrationTest {

    private static final String TABLE = "hello.zz_rls_matrix";

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID LOCATION_1 = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID A_AT_1_WITH_B = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final UUID A_WIDE = UUID.fromString("00000000-0000-0000-0000-000000000a02");
    private static final UUID B_WITH_A = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    private static final UUID C_ALONE = UUID.fromString("00000000-0000-0000-0000-000000000c01");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void createTheTableWithTheTemplate() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("drop table if exists " + TABLE);
        admin.execute("create table " + TABLE + " (row_id uuid primary key, owner_entity_id uuid not null,"
                + " counterparty_entity_id uuid, location_id uuid)");
        admin.execute("grant select, insert on " + TABLE + " to app_rw");
        // The template, verbatim (RLS_POLICY_TEMPLATE.md).
        admin.execute("alter table " + TABLE + " enable row level security");
        admin.execute("alter table " + TABLE + " force row level security");
        admin.execute("create policy own_read on " + TABLE + " for select to app_rw"
                + " using (kernel.scope_class() = 'OWN' and owner_entity_id = kernel.scope_entity()"
                + " and (kernel.scope_location() is null or location_id = kernel.scope_location()))");
        admin.execute("create policy own_write on " + TABLE + " for insert to app_rw"
                + " with check (kernel.scope_class() = 'OWN' and owner_entity_id = kernel.scope_entity())");
        admin.execute("create policy party_read on " + TABLE + " for select to app_rw"
                + " using (kernel.scope_class() in ('OWN', 'PARTY')"
                + " and (owner_entity_id = kernel.scope_entity() or counterparty_entity_id = kernel.scope_entity())"
                + " and (kernel.scope_location() is null or location_id = kernel.scope_location()))");
        admin.execute("create policy fed_view on " + TABLE + " for select to app_rw"
                + " using (kernel.scope_class() = 'FEDERATION_VIEW')");
        admin.execute("create policy ext_view on " + TABLE + " for select to app_rw"
                + " using (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'"
                + " and owner_entity_id = any (kernel.granted_entities()))");

        insert(admin, A_AT_1_WITH_B, A, B, LOCATION_1);
        insert(admin, A_WIDE, A, null, null);
        insert(admin, B_WITH_A, B, A, null);
        insert(admin, C_ALONE, C, null, null);
    }

    @AfterEach
    void dropTheTable() {
        superuserJdbc().execute("drop table if exists " + TABLE);
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    void ownEntityWideReadsItsRowsAndTheRowsWhereItIsTheCounterparty() {
        // B_WITH_A is B's document with A as the buyer: A reads it in its own scope (through the
        // masking view, M4). C's row is nobody's business but C's.
        assertThat(visible(A, null, "OWN", "{}")).containsExactlyInAnyOrder(A_AT_1_WITH_B, A_WIDE, B_WITH_A);
    }

    @Test
    void ownAtALocationReadsThatLocationOnly() {
        // A's entity-wide row has no location, so a location-scoped user does not see it either.
        assertThat(visible(A, LOCATION_1, "OWN", "{}")).containsExactly(A_AT_1_WITH_B);
    }

    @Test
    void partyReadsTheRowsItIsASideOf() {
        // B owns one row and is the counterparty of A's row at location 1; A's other row and
        // C's are not B's business.
        assertThat(visible(B, null, "PARTY", "{}")).containsExactlyInAnyOrder(B_WITH_A, A_AT_1_WITH_B);
    }

    @Test
    void federationViewReadsEverything() {
        assertThat(visible(A, null, "FEDERATION_VIEW", "{}"))
                .containsExactlyInAnyOrder(A_AT_1_WITH_B, A_WIDE, B_WITH_A, C_ALONE);
    }

    @Test
    void externalReadsItsGrantedEntitiesAndNothingWithAnEmptyGrant() {
        assertThat(visible(A, null, "EXTERNAL_TIMEBOXED", "{" + C + "}")).containsExactly(C_ALONE);
        assertThat(visible(A, null, "EXTERNAL_TIMEBOXED", "{" + B + "," + C + "}"))
                .containsExactlyInAnyOrder(B_WITH_A, C_ALONE);
        assertThat(visible(A, null, "EXTERNAL_TIMEBOXED", "{}")).isEmpty();
    }

    @Test
    void noneReadsNothing() {
        assertThat(visible(null, null, "NONE", "{}")).isEmpty();
    }

    @Test
    void aTransactionThatForgotTheScopeReadsNothing() {
        List<UUID> rows = new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.queryForList("select row_id from " + TABLE, UUID.class));
        assertThat(rows).isEmpty();
    }

    // ---- writes --------------------------------------------------------------------------------

    @Test
    void ownWritesItsOwnRowsAndNobodyElses() {
        UUID mine = UUID.randomUUID();
        inScope(A, null, "OWN", "{}", () -> insert(jdbc, mine, A, null, null));
        assertThat(visible(A, null, "OWN", "{}")).contains(mine);

        assertThatThrownBy(() -> inScope(A, null, "OWN", "{}", () -> insert(jdbc, UUID.randomUUID(), B, null, null)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theReadOnlyClassesWriteNothing() {
        for (String readOnly : List.of("PARTY", "FEDERATION_VIEW", "EXTERNAL_TIMEBOXED", "NONE")) {
            assertThatThrownBy(() -> inScope(
                            A, null, readOnly, "{" + A + "}", () -> insert(jdbc, UUID.randomUUID(), A, null, null)))
                    .as(readOnly)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private List<UUID> visible(UUID entity, UUID location, String policyClass, String granted) {
        return inScope(
                entity,
                location,
                policyClass,
                granted,
                () -> jdbc.queryForList("select row_id from " + TABLE + " order by row_id", UUID.class));
    }

    /** What ScopeConnectionCustomizer does, by hand: the four settings, local to one transaction. */
    private <T> T inScope(UUID entity, UUID location, String policyClass, String granted, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("select set_config('app.scope_entity_id', ?, true)", String.class, text(entity));
            jdbc.queryForObject("select set_config('app.scope_location_id', ?, true)", String.class, text(location));
            jdbc.queryForObject("select set_config('app.scope_class', ?, true)", String.class, policyClass);
            jdbc.queryForObject("select set_config('app.granted_entities', ?, true)", String.class, granted);
            return work.get();
        });
    }

    private static String text(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static int insert(JdbcTemplate db, UUID rowId, UUID owner, UUID counterparty, UUID location) {
        return db.update(
                "insert into " + TABLE + " (row_id, owner_entity_id, counterparty_entity_id, location_id)"
                        + " values (?, ?, ?, ?)",
                rowId,
                owner,
                counterparty,
                location);
    }
}
