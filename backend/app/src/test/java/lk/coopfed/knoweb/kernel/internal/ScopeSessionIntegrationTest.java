package lk.coopfed.knoweb.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ScopeSessionIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A =
            UUID.fromString("0190f000-0000-7000-8000-000000000001");

    private static final UUID ENTITY_B =
            UUID.fromString("0190f000-0000-7000-8000-000000000002");

    private static final UUID LOCATION_A =
            UUID.fromString("0190f000-0000-7000-8000-000000000101");

    @Test
    void grantedEntitiesSerialisesAsPostgresUuidArray() {
        assertThat(GrantedEntities.settingValue(Set.of()))
                .isEqualTo("{}");

        assertThat(GrantedEntities.settingValue(Set.of(ENTITY_B, ENTITY_A)))
                .isEqualTo(
                        "{0190f000-0000-7000-8000-000000000001,"
                                + "0190f000-0000-7000-8000-000000000002}");
    }

    @Test
    void kernelGrantedEntitiesDefaultsToEmptyArray() {
        JdbcTemplate db = superuserJdbc();

        String value = db.queryForObject(
                "select kernel.granted_entities()::text",
                String.class);

        assertThat(value).isEqualTo("{}");
    }

    @Test
    void ownScopeValuesAreVisibleInsideTransaction() {
        JdbcTemplate db = superuserJdbc();
        TransactionTemplate tx = transaction(db);

        tx.executeWithoutResult(status -> {
            setScope(
                    db,
                    ENTITY_A.toString(),
                    LOCATION_A.toString(),
                    "OWN",
                    "{}");

            assertThat(db.queryForObject(
                    "select kernel.scope_entity()",
                    UUID.class))
                    .isEqualTo(ENTITY_A);

            assertThat(db.queryForObject(
                    "select kernel.scope_location()",
                    UUID.class))
                    .isEqualTo(LOCATION_A);

            assertThat(db.queryForObject(
                    "select kernel.scope_class()",
                    String.class))
                    .isEqualTo("OWN");

            assertThat(db.queryForObject(
                    "select kernel.granted_entities()::text",
                    String.class))
                    .isEqualTo("{}");
        });
    }

    @Test
    void noneScopeFailsClosed() {
        JdbcTemplate db = superuserJdbc();
        TransactionTemplate tx = transaction(db);

        tx.executeWithoutResult(status -> {
            setScope(
                    db,
                    "",
                    "",
                    "NONE",
                    "{}");

            assertThat(db.queryForObject(
                    "select kernel.scope_entity()",
                    UUID.class))
                    .isNull();

            assertThat(db.queryForObject(
                    "select kernel.scope_location()",
                    UUID.class))
                    .isNull();

            assertThat(db.queryForObject(
                    "select kernel.scope_class()",
                    String.class))
                    .isEqualTo("NONE");

            assertThat(db.queryForObject(
                    "select kernel.granted_entities()::text",
                    String.class))
                    .isEqualTo("{}");
        });
    }

    @Test
    void externalScopeCarriesOnlyGrantedEntities() {
        JdbcTemplate db = superuserJdbc();
        TransactionTemplate tx = transaction(db);

        String grants = GrantedEntities.settingValue(
                Set.of(ENTITY_A, ENTITY_B));

        tx.executeWithoutResult(status -> {
            setScope(
                    db,
                    ENTITY_A.toString(),
                    "",
                    "EXTERNAL_TIMEBOXED",
                    grants);

            assertThat(db.queryForObject(
                    "select kernel.scope_class()",
                    String.class))
                    .isEqualTo("EXTERNAL_TIMEBOXED");

            assertThat(db.queryForObject(
                    "select kernel.granted_entities()::text",
                    String.class))
                    .isEqualTo(
                            "{0190f000-0000-7000-8000-000000000001,"
                                    + "0190f000-0000-7000-8000-000000000002}");

            Boolean entityAVisible = db.queryForObject(
                    "select ?::uuid = any(kernel.granted_entities())",
                    Boolean.class,
                    ENTITY_A);

            Boolean entityBVisible = db.queryForObject(
                    "select ?::uuid = any(kernel.granted_entities())",
                    Boolean.class,
                    ENTITY_B);

            assertThat(entityAVisible).isTrue();
            assertThat(entityBVisible).isTrue();
        });
    }

    @Test
    void transactionLocalScopeDoesNotLeakIntoNextTransaction() {
        JdbcTemplate db = superuserJdbc();
        TransactionTemplate tx = transaction(db);

        tx.executeWithoutResult(status ->
                setScope(
                        db,
                        ENTITY_A.toString(),
                        LOCATION_A.toString(),
                        "OWN",
                        GrantedEntities.settingValue(Set.of(ENTITY_B))));

        tx.executeWithoutResult(status -> {
            assertThat(db.queryForObject(
                    "select kernel.scope_entity()",
                    UUID.class))
                    .isNull();

            assertThat(db.queryForObject(
                    "select kernel.scope_location()",
                    UUID.class))
                    .isNull();

            assertThat(db.queryForObject(
                    "select kernel.scope_class()",
                    String.class))
                    .isEqualTo("NONE");

            assertThat(db.queryForObject(
                    "select kernel.granted_entities()::text",
                    String.class))
                    .isEqualTo("{}");
        });
    }

    private static TransactionTemplate transaction(JdbcTemplate db) {
        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(
                        db.getDataSource());

        return new TransactionTemplate(manager);
    }

    private static void setScope(
            JdbcTemplate db,
            String entityId,
            String locationId,
            String policyClass,
            String grantedEntities) {

        db.queryForList(
                """
                select
                    set_config('app.scope_entity_id', ?, true),
                    set_config('app.scope_location_id', ?, true),
                    set_config('app.scope_class', ?, true),
                    set_config('app.granted_entities', ?, true)
                """,
                entityId,
                locationId,
                policyClass,
                grantedEntities);
    }
}
