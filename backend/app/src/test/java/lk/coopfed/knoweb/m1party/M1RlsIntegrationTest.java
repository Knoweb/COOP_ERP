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
                    party.location,
                    party.entity_relationship,
                    party.entity_party_directory,
                    party.federation_identity,
                    party.entity
                cascade
                """);

        insertEntity(admin, ENTITY_A, "FED001", "FEDERATION", "Cooperative Federation");

        insertEntity(admin, ENTITY_B, "M001", "MPCS", "MPCS One");

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
    void entityWideOwnScopeCanRegisterMpcsThroughEntityRegistrationPolicy() {
        UUID newEntity = UUID.fromString("00000000-0000-0000-0000-000000000301");

        inScope(ENTITY_A, null, "OWN", () -> {
            int inserted = insertEntity(jdbc, newEntity, "M301", "MPCS", "MPCS 301");

            assertThat(inserted).isEqualTo(1);

            return null;
        });
    }

    @Test
    void locationScopedOwnScopeCannotRegisterAnotherEntity() {
        UUID newEntity = UUID.fromString("00000000-0000-0000-0000-000000000302");

        assertThatThrownBy(() -> inScope(ENTITY_A, LOCATION_A1, "OWN", () -> {
                    insertEntity(jdbc, newEntity, "M302", "MPCS", "MPCS 302");

                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void federationViewCannotRegisterEntity() {
        UUID newEntity = UUID.fromString("00000000-0000-0000-0000-000000000303");

        assertThatThrownBy(() -> inScope(ENTITY_A, null, "FEDERATION_VIEW", () -> {
                    insertEntity(jdbc, newEntity, "M303", "MPCS", "MPCS 303");

                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void noneScopeCannotRegisterEntity() {
        UUID newEntity = UUID.fromString("00000000-0000-0000-0000-000000000304");

        assertThatThrownBy(() -> inScope(null, null, "NONE", () -> {
                    insertEntity(jdbc, newEntity, "M304", "MPCS", "MPCS 304");

                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void externalTimedScopeCannotRegisterEntity() {
        UUID newEntity = UUID.fromString("00000000-0000-0000-0000-000000000305");

        assertThatThrownBy(() -> inScope(ENTITY_A, null, "EXTERNAL_TIMEBOXED", () -> {
                    insertEntity(jdbc, newEntity, "M305", "MPCS", "MPCS 305");

                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void entityRegistrationPolicyCannotCreateAnotherFederation() {
        UUID newEntity = UUID.fromString("00000000-0000-0000-0000-000000000306");

        assertThatThrownBy(() -> inScope(ENTITY_A, null, "OWN", () -> {
                    insertEntity(jdbc, newEntity, "F306", "FEDERATION", "Illegal Federation");

                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void externalScopeReadsNothingWithoutExternalViewPolicy() {
        List<UUID> visible = visibleLocations(ENTITY_A, null, "EXTERNAL_TIMEBOXED");

        assertThat(visible).isEmpty();
    }

    // ---- party.entity_party_directory (V0003, restricted by V0004) ----------------------------
    //
    // The directory is what a counterparty may know of an entity: its legal name. Before V0004
    // every PARTY-class caller read every entity's name; now it reads its own and those of the
    // entities it has an active relationship with.

    private static final UUID ENTITY_C = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    void aPartyCallerReadsItsOwnNameAndItsActiveCounterpartiesOnly() {
        JdbcTemplate admin = superuserJdbc();
        insertEntity(admin, ENTITY_C, "M002", "MPCS", "MPCS Two");
        insertRelationship(admin, ENTITY_A, ENTITY_B, "ACTIVE"); // the Federation sells to B
        insertRelationship(admin, ENTITY_A, ENTITY_C, "DRAFT"); // and not yet to C

        assertThat(visibleDirectory(ENTITY_B)).containsExactlyInAnyOrder(ENTITY_B, ENTITY_A);
        assertThat(visibleDirectory(ENTITY_C))
                .as("a DRAFT relationship shows nobody")
                .containsExactly(ENTITY_C);
    }

    @Test
    void aPartyCallerWithNoRelationshipReadsNoOtherName() {
        assertThat(visibleDirectory(ENTITY_B)).containsExactly(ENTITY_B);
    }

    @Test
    void aPartyCallerReadsOnlyTheRelationshipsItIsASideOf() {
        JdbcTemplate admin = superuserJdbc();
        insertEntity(admin, ENTITY_C, "M002", "MPCS", "MPCS Two");
        insertRelationship(admin, ENTITY_A, ENTITY_B, "ACTIVE");
        insertRelationship(admin, ENTITY_A, ENTITY_C, "ACTIVE");

        List<UUID> buyers = inScope(
                ENTITY_B,
                null,
                "PARTY",
                () -> jdbc.queryForList("select buyer_entity_id from party.entity_relationship", UUID.class));

        assertThat(buyers).containsExactly(ENTITY_B);
    }

    @Test
    void theApplicationCannotWriteTheDirectoryAndTheTriggerStillCan() {
        JdbcTemplate admin = superuserJdbc();

        // The trigger (SECURITY DEFINER, as the migrator, a member of app_seed) filled the
        // directory when the entities were inserted, under FORCE ROW LEVEL SECURITY.
        assertThat(admin.queryForObject("select count(*) from party.entity_party_directory", Integer.class))
                .isEqualTo(2);

        assertThatThrownBy(() -> inScope(
                        ENTITY_B,
                        null,
                        "OWN",
                        () -> jdbc.update(
                                "insert into party.entity_party_directory (entity_id, legal_name_en) values (?, ?)",
                                ENTITY_B,
                                "renamed")))
                .isInstanceOf(DataAccessException.class);
    }

    private List<UUID> visibleDirectory(UUID callerEntity) {
        return inScope(
                callerEntity,
                null,
                "PARTY",
                () -> jdbc.queryForList(
                        "select entity_id from party.entity_party_directory order by entity_id", UUID.class));
    }

    private static void insertRelationship(JdbcTemplate admin, UUID seller, UUID buyer, String status) {
        admin.update(
                """
                insert into party.entity_relationship (
                    relationship_id, seller_entity_id, buyer_entity_id, effective_from, status
                )
                values (gen_random_uuid(), ?, ?, current_date, ?)
                """,
                seller,
                buyer,
                status);
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
                /*
                 * Every test transaction is disposable.
                 * Fixtures are owned by the superuser setup above.
                 */
                status.setRollbackOnly();
            }
        });
    }

    private static String setting(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static int insertEntity(JdbcTemplate jdbc, UUID entityId, String code, String type, String legalNameEn) {

        return jdbc.update(
                """
                insert into party.entity (
                    entity_id,
                    entity_code,
                    entity_type,
                    legal_name_en
                )
                values (?, ?, ?, ?)
                """,
                entityId,
                code,
                type,
                legalNameEn);
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
