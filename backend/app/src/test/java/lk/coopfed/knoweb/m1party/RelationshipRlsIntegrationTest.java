package lk.coopfed.knoweb.m1party;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The RLS matrix rows of party.entity_relationship (21A section 9) as the application user,
 * after m1party V0007: the seller reads and writes its rows; the buyer (the counterparty) reads
 * them in OWN and in PARTY scope and writes nothing; the Federation view reads all and writes
 * nothing; a regulator reads what its grant covers; nobody else reads anything. Also the A-I3
 * exclusion constraint and the narrow party.trading_standing function.
 */
class RelationshipRlsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-00000000f101");
    private static final UUID DISTRIBUTOR = UUID.fromString("00000000-0000-0000-0000-00000000f102");
    private static final UUID SOCIETY = UUID.fromString("00000000-0000-0000-0000-00000000f103");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000f104");

    private static final UUID FED_TO_DISTRIBUTOR = UUID.fromString("00000000-0000-0000-0000-00000000f201");
    private static final UUID DISTRIBUTOR_TO_SOCIETY = UUID.fromString("00000000-0000-0000-0000-00000000f202");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void theChain() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        insertEntity(admin, FEDERATION, "FED", "FEDERATION");
        insertEntity(admin, DISTRIBUTOR, "D01", "DISTRIBUTOR");
        insertEntity(admin, SOCIETY, "M01", "MPCS");
        insertEntity(admin, STRANGER, "M02", "MPCS");
        insertRelationship(admin, FED_TO_DISTRIBUTOR, FEDERATION, DISTRIBUTOR, "ACTIVE", "2026-01-01", null);
        insertRelationship(admin, DISTRIBUTOR_TO_SOCIETY, DISTRIBUTOR, SOCIETY, "ACTIVE", "2026-01-01", null);
    }

    @AfterEach
    void clean() {
        superuserJdbc()
                .execute("truncate table party.entity_relationship, party.entity_party_directory,"
                        + " party.federation_identity, party.entity cascade");
    }

    // ---- reads ----------------------------------------------------------------------------

    @Test
    void theDistributorReadsWhatItSellsAndWhatItBuys() {
        assertThat(visible(DISTRIBUTOR, "OWN", null))
                .containsExactlyInAnyOrder(FED_TO_DISTRIBUTOR, DISTRIBUTOR_TO_SOCIETY);
    }

    @Test
    void theBuyerReadsItsSideInOwnScope() {
        // V0007: party_read admits OWN, as the template writes it; V0004 admitted PARTY only.
        assertThat(visible(SOCIETY, "OWN", null)).containsExactly(DISTRIBUTOR_TO_SOCIETY);
    }

    @Test
    void theBuyerReadsItsSideInPartyScope() {
        assertThat(visible(SOCIETY, "PARTY", null)).containsExactly(DISTRIBUTOR_TO_SOCIETY);
    }

    @Test
    void aStrangerReadsNothingInAnyClassButItsOwn() {
        assertThat(visible(STRANGER, "OWN", null)).isEmpty();
        assertThat(visible(STRANGER, "PARTY", null)).isEmpty();
    }

    @Test
    void theFederationViewReadsEverything() {
        assertThat(visible(STRANGER, "FEDERATION_VIEW", null))
                .containsExactlyInAnyOrder(FED_TO_DISTRIBUTOR, DISTRIBUTOR_TO_SOCIETY);
    }

    @Test
    void aRegulatorReadsTheRelationshipsItsGrantCoversAsSeller() {
        assertThat(visible(STRANGER, "EXTERNAL_TIMEBOXED", "{" + DISTRIBUTOR + "}"))
                .containsExactly(DISTRIBUTOR_TO_SOCIETY);
        assertThat(visible(STRANGER, "EXTERNAL_TIMEBOXED", "{}")).isEmpty();
    }

    @Test
    void noScopeReadsNothing() {
        assertThat(visible(null, "NONE", null)).isEmpty();
    }

    // ---- writes ---------------------------------------------------------------------------

    @Test
    void theBuyerCannotChangeTheTermsItReads() {
        int updated = inScope(
                SOCIETY,
                "OWN",
                null,
                () -> jdbc.update(
                        "update party.entity_relationship set credit_limit = 1 where relationship_id = ?",
                        DISTRIBUTOR_TO_SOCIETY));
        assertThat(updated).isZero();
    }

    @Test
    void theSellerChangesItsOwnRow() {
        int updated = inScope(
                DISTRIBUTOR,
                "OWN",
                null,
                () -> jdbc.update(
                        "update party.entity_relationship set status = 'SUSPENDED' where relationship_id = ?",
                        DISTRIBUTOR_TO_SOCIETY));
        assertThat(updated).isEqualTo(1);
    }

    @Test
    void nobodyOpensARelationshipInAnotherEntitysName() {
        assertThatThrownBy(() -> inScope(SOCIETY, "OWN", null, () -> {
                    insertRelationship(jdbc, UUID.randomUUID(), DISTRIBUTOR, SOCIETY, "DRAFT", "2027-01-01", null);
                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> inScope(DISTRIBUTOR, "FEDERATION_VIEW", null, () -> {
                    insertRelationship(jdbc, UUID.randomUUID(), DISTRIBUTOR, SOCIETY, "DRAFT", "2027-01-01", null);
                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> inScope(DISTRIBUTOR, "PARTY", null, () -> {
                    insertRelationship(jdbc, UUID.randomUUID(), DISTRIBUTOR, SOCIETY, "DRAFT", "2027-01-01", null);
                    return null;
                }))
                .isInstanceOf(DataAccessException.class);
    }

    // ---- A-I3: one ACTIVE row per pair per date ---------------------------------------------

    @Test
    void twoActiveRowsOfOnePairMayNotShareADate() {
        JdbcTemplate admin = superuserJdbc();
        assertThatThrownBy(() -> insertRelationship(
                        admin, UUID.randomUUID(), DISTRIBUTOR, SOCIETY, "ACTIVE", "2026-06-01", "2026-06-30"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adjacentRangesADraftBesideAnActiveRowAndAnotherPairAreAllowed() {
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "update party.entity_relationship set effective_to = '2026-06-30' where relationship_id = ?",
                DISTRIBUTOR_TO_SOCIETY);
        insertRelationship(admin, UUID.randomUUID(), DISTRIBUTOR, SOCIETY, "ACTIVE", "2026-07-01", null);
        insertRelationship(admin, UUID.randomUUID(), DISTRIBUTOR, SOCIETY, "DRAFT", "2026-07-01", null);
        insertRelationship(admin, UUID.randomUUID(), DISTRIBUTOR, STRANGER, "ACTIVE", "2026-01-01", null);
        assertThat(admin.queryForObject("select count(*) from party.entity_relationship", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void anUnknownAllocationRuleIsRefusedByTheTable() {
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update party.entity_relationship set allocation_rule = 'LOTTERY' where relationship_id = ?",
                                DISTRIBUTOR_TO_SOCIETY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- party.trading_standing -------------------------------------------------------------

    @Test
    void theStandingFunctionAnswersTwoFactsToAnOwnCallerOnly() {
        List<String> asSeller = inScope(
                DISTRIBUTOR,
                "OWN",
                null,
                () -> jdbc.query(
                        "select entity_type, status from party.trading_standing(?)",
                        (rs, row) -> rs.getString(1) + " " + rs.getString(2),
                        SOCIETY));
        assertThat(asSeller).containsExactly("MPCS ONBOARDING");

        // The seller still cannot read the buyer's entity row itself.
        List<UUID> rows = inScope(
                DISTRIBUTOR,
                "OWN",
                null,
                () -> jdbc.queryForList("select entity_id from party.entity where entity_id = ?", UUID.class, SOCIETY));
        assertThat(rows).isEmpty();

        List<String> asParty = inScope(
                DISTRIBUTOR,
                "PARTY",
                null,
                () -> jdbc.query(
                        "select entity_type from party.trading_standing(?)", (rs, row) -> rs.getString(1), SOCIETY));
        assertThat(asParty).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private List<UUID> visible(UUID entity, String policyClass, String grantedEntities) {
        return inScope(
                entity,
                policyClass,
                grantedEntities,
                () -> jdbc.queryForList(
                        "select relationship_id from party.entity_relationship order by relationship_id", UUID.class));
    }

    private <T> T inScope(UUID entity, String policyClass, String grantedEntities, Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            jdbc.queryForObject(
                    "select set_config('app.scope_entity_id', ?, true)",
                    String.class,
                    entity == null ? "" : entity.toString());
            jdbc.queryForObject("select set_config('app.scope_location_id', '', true)", String.class);
            jdbc.queryForObject("select set_config('app.scope_class', ?, true)", String.class, policyClass);
            jdbc.queryForObject(
                    "select set_config('app.granted_entities', ?, true)",
                    String.class,
                    grantedEntities == null ? "{}" : grantedEntities);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private static void insertEntity(JdbcTemplate admin, UUID id, String code, String type) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status)"
                        + " values (?, ?, ?, ?, ?)",
                id,
                code,
                type,
                code,
                "MPCS".equals(type) ? "ONBOARDING" : "ACTIVE");
    }

    private static void insertRelationship(
            JdbcTemplate db, UUID id, UUID seller, UUID buyer, String status, String from, String to) {
        db.update(
                """
                insert into party.entity_relationship (
                    relationship_id, seller_entity_id, buyer_entity_id, status, effective_from, effective_to
                )
                values (?, ?, ?, ?, cast(? as date), cast(? as date))
                """,
                id,
                seller,
                buyer,
                status,
                from,
                to);
    }
}
