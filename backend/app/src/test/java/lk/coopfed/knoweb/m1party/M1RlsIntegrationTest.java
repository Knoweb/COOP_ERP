package lk.coopfed.knoweb.m1party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class M1RlsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID ENTITY_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final UUID LOCATION_A1 = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID LOCATION_A2 = UUID.fromString("00000000-0000-0000-0000-000000000102");

    private static final UUID LOCATION_B1 = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedLocations() {
        JdbcTemplate admin = superuserJdbc();

        admin.execute(
                """
                truncate table
                    party.device,
                    party.till_position,
                    party.location
                cascade
                """);

        insertLocation(admin, LOCATION_A1, ENTITY_A, "A-SHOP-1", "Entity A Shop 1");
        insertLocation(admin, LOCATION_A2, ENTITY_A, "A-SHOP-2", "Entity A Shop 2");
        insertLocation(admin, LOCATION_B1, ENTITY_B, "B-SHOP-1", "Entity B Shop 1");
    }

    @Test
    void ownScopeReadsOnlyItsEntityRows() {
        List<UUID> visible = visibleLocations(ENTITY_A, null, "OWN");

        assertThat(visible).containsExactlyInAnyOrder(LOCATION_A1, LOCATION_A2);
    }

    @Test
    void locationScopedOwnUserCannotReadSiblingShop() {
        List<UUID> visible = visibleLocations(ENTITY_A, LOCATION_A1, "OWN");

        assertThat(visible).containsExactly(LOCATION_A1);
    }

    @Test
    void federationViewReadsAcrossEntities() {
        List<UUID> visible = visibleLocations(ENTITY_A, null, "FEDERATION_VIEW");

        assertThat(visible).containsExactlyInAnyOrder(LOCATION_A1, LOCATION_A2, LOCATION_B1);
    }

    @Test
    void federationViewCannotWrite() {
        UUID attemptedLocation = UUID.fromString("00000000-0000-0000-0000-000000000999");

        assertThatThrownBy(() -> inScope(ENTITY_A, null, "FEDERATION_VIEW", () -> {
                    jdbc.update(
                            """
                                    insert into party.location (
                                        location_id,
                                        owner_entity_id,
                                        location_code,
                                        location_type,
                                        name_en
                                    )
                                    values (?, ?, ?, 'SHOP', ?)
                                    """,
                            attemptedLocation,
                            ENTITY_A,
                            "FED-ILLEGAL",
                            "Federation Illegal Write");

                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void externalScopeFailsClosedUntilGrantedEntitiesHelperIsAvailable() {
        /*
         * K-01 must later add kernel.granted_entities() and the ext_view policy.
         *
         * Until that dependency exists, EXTERNAL_TIMEBOXED must not accidentally
         * fall through the OWN policy merely because scope_entity_id happens to
         * match a row owner.
         */
        List<UUID> visible = visibleLocations(ENTITY_A, null, "EXTERNAL_TIMEBOXED");

        assertThat(visible).isEmpty();
    }

    @Test
    void noneScopeReadsNothing() {
        List<UUID> visible = visibleLocations(null, null, "NONE");

        assertThat(visible).isEmpty();
    }

    private List<UUID> visibleLocations(UUID entityId, UUID locationId, String policyClass) {

        return inScope(
                entityId,
                locationId,
                policyClass,
                () -> jdbc.queryForList(
                        """
                                select location_id
                                  from party.location
                                 order by location_id
                                """,
                        UUID.class));
    }

    private <T> T inScope(UUID entityId, UUID locationId, String policyClass, Supplier<T> work) {

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        return transaction.execute(status -> {
            jdbc.queryForObject("select set_config('app.scope_entity_id', ?, true)", String.class, setting(entityId));

            jdbc.queryForObject(
                    "select set_config('app.scope_location_id', ?, true)", String.class, setting(locationId));

            jdbc.queryForObject("select set_config('app.scope_class', ?, true)", String.class, policyClass);

            try {
                return work.get();
            } finally {
                // Every test transaction is disposable.
                // Fixtures are owned by the superuser setup above.
                status.setRollbackOnly();
            }
        });
    }

    private static String setting(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static void insertLocation(
            JdbcTemplate admin, UUID locationId, UUID ownerEntityId, String code, String name) {

        admin.update(
                """
                        insert into party.location (
                            location_id,
                            owner_entity_id,
                            location_code,
                            location_type,
                            name_en
                        )
                        values (?, ?, ?, 'SHOP', ?)
                        """,
                locationId,
                ownerEntityId,
                code,
                name);
    }
}
