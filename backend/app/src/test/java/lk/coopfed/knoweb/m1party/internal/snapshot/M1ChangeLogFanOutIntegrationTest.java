package lk.coopfed.knoweb.m1party.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.m1party.api.UserDeactivated;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class M1ChangeLogFanOutIntegrationTest extends PostgresIntegrationTest {

    static final UUID ENTITY = UUID.fromString("0190a810-0000-7000-8900-000000000001");
    static final UUID SHOP = UUID.fromString("0190a810-0000-7000-8900-000000000101");
    static final UUID CASHIER = UUID.fromString("0190a810-0000-7000-8900-000000000401");
    static final UUID ROLE = UUID.fromString("0190a810-0000-7000-8900-000000000501");
    static final UUID TEMPLATE = UUID.fromString("0190a810-0000-7000-8900-000000000502");
    static final UUID OTHER_ENTITY = UUID.fromString("0190a810-0000-7000-8900-000000000002");

    @Autowired
    M1ChangeLogFanOut fanOut;

    @Autowired
    ChangeLog changeLog;

    @Autowired
    SystemScope transactions;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.sync.SnapshotBuilder snapshotBuilder;

    @Autowired
    JdbcTemplate appJdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final ScopeContext central = SystemScope.own(ENTITY, null);
    private final ScopeContext till = SystemScope.own(ENTITY, SHOP);
    private final ScopeContext federation = SystemScope.own(TEST_FEDERATION, null);

    @BeforeEach
    void setupData() {
        JdbcTemplate db = superuserJdbc();
        removeTheTemplate();
        db.update("delete from security.user_role where scope_entity_id = ?", ENTITY);
        db.update("delete from security.role_permission where role_id = ?", ROLE);
        db.update("delete from security.app_user where home_entity_id = ?", ENTITY);
        db.update("delete from security.role where owner_entity_id = ?", ENTITY);
        db.update("delete from party.till_position where owner_entity_id = ?", ENTITY);
        db.update("delete from party.location where owner_entity_id = ?", ENTITY);
        db.update("delete from party.entity_party_directory where entity_id = ?", ENTITY);
        db.update("delete from party.entity where entity_id = ?", ENTITY);
        db.update("delete from kernel.change_log where location_id = ?", SHOP);

        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) values (?, 'MPCS', 'ENT', 'Snapshot Entity', 'ACTIVE')",
                ENTITY);
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en,
                                            language, status)
                values (?, ?, 'M1S1', 'SHOP', 'Snapshot shop', 'en', 'ACTIVE')
                """,
                SHOP,
                ENTITY);

        db.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status, pin_hash) values (?, ?, 'cashier', 'Cashier', 'TILL', 'ACTIVE', 'hash')",
                CASHIER,
                ENTITY);
        db.update(
                "insert into security.role (role_id, owner_entity_id, name_en) values (?, ?, 'Cashier')", ROLE, ENTITY);
        db.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'prt.location.view')",
                ROLE);
        db.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                CASHIER,
                ROLE,
                ENTITY,
                SHOP);
    }

    /** The template is the only row of the class outside ENTITY: other tests list the templates. */
    @AfterEach
    void removeTheTemplate() {
        JdbcTemplate db = superuserJdbc();
        db.update("delete from security.user_role where role_id = ?", TEMPLATE);
        db.update("delete from security.role_permission where role_id = ?", TEMPLATE);
        db.update("delete from security.role where role_id = ?", TEMPLATE);
    }

    @Test
    void userDeactivatedGeneratesTombstoneInNextDelta() throws Exception {
        // Initialize the till's version so it has a delta baseline
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new lk.coopfed.knoweb.kernel.api.ChangeLog.Target(ENTITY, SHOP)),
                    List.of(lk.coopfed.knoweb.kernel.api.ChangeLog.Change.upsert("operator", CASHIER)),
                    null,
                    false,
                    central);
            return null;
        });

        // Initially, the cashier is in the till's full snapshot
        Object initial = transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30)));
        com.fasterxml.jackson.databind.JsonNode initialJson = mapper.valueToTree(initial);
        assertThat(initialJson.path("tables").path("operator").isMissingNode()).isFalse();
        assertThat(initialJson.path("tables").path("operator").path("upserts").toString())
                .contains(CASHIER.toString());

        long tillVersion = initialJson.path("version").asLong();

        // The central operator deactivates the cashier. The row stays in app_user but status becomes DEACTIVATED.
        superuserJdbc().update("update security.app_user set status = 'DEACTIVATED' where user_id = ?", CASHIER);
        UserDeactivated event = new UserDeactivated(CASHIER, CASHIER, ENTITY, "TILL", "DEACTIVATED");

        // The worker consumer processes the event
        transactions.inOwnTransaction(central, () -> {
            fanOut.onUrgentUserEvent(mapper.valueToTree(event), central);
            return null;
        });

        // The till fetches its next delta
        Object nextDelta = transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30)));
        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(nextDelta);

        // The delta must contain a tombstone for the cashier
        assertThat(nextJson.path("tables").path("operator").isMissingNode()).isFalse();
        assertThat(nextJson.path("tables").path("operator").path("tombstones").toString())
                .as("Operator row must be a tombstone to refuse offline sign-in")
                .contains(CASHIER.toString());
    }

    @Test
    void pinResetProducesUrgentOperatorUpdate() throws Exception {
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new lk.coopfed.knoweb.kernel.api.ChangeLog.Target(ENTITY, SHOP)),
                    List.of(lk.coopfed.knoweb.kernel.api.ChangeLog.Change.upsert("operator", CASHIER)),
                    null,
                    false,
                    central);
            return null;
        });

        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        superuserJdbc().update("update security.app_user set pin_hash = 'newhash' where user_id = ?", CASHIER);
        lk.coopfed.knoweb.m1party.api.UserCredentialReset event = new lk.coopfed.knoweb.m1party.api.UserCredentialReset(
                CASHIER, CASHIER, ENTITY, "TILL", "ACTIVE", "PIN");

        transactions.inOwnTransaction(central, () -> {
            fanOut.onUrgentUserEvent(mapper.valueToTree(event), central);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("urgent").asBoolean()).isTrue();
        assertThat(nextJson.path("tables").path("operator").path("upserts").toString())
                .contains("newhash");
    }

    @Test
    void roleRevokedProducesUrgentTombstone() throws Exception {
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new lk.coopfed.knoweb.kernel.api.ChangeLog.Target(ENTITY, SHOP)),
                    List.of(lk.coopfed.knoweb.kernel.api.ChangeLog.Change.upsert("operator", CASHIER)),
                    null,
                    false,
                    central);
            return null;
        });

        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        superuserJdbc().update("delete from security.user_role where user_id = ?", CASHIER);
        lk.coopfed.knoweb.m1party.api.RoleRevoked event =
                new lk.coopfed.knoweb.m1party.api.RoleRevoked(ROLE, CASHIER, ENTITY, SHOP);

        transactions.inOwnTransaction(central, () -> {
            fanOut.onRoleRevoked(mapper.valueToTree(event), central);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("urgent").asBoolean()).isTrue();
        assertThat(nextJson.path("tables").path("operator").path("tombstones").toString())
                .contains(CASHIER.toString());
    }

    @Test
    void rolePermissionChangesReachShop() throws Exception {
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new lk.coopfed.knoweb.kernel.api.ChangeLog.Target(ENTITY, SHOP)),
                    List.of(lk.coopfed.knoweb.kernel.api.ChangeLog.Change.upsert("operator", CASHIER)),
                    null,
                    false,
                    central);
            return null;
        });

        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        superuserJdbc().update("delete from security.role_permission where role_id = ?", ROLE);
        lk.coopfed.knoweb.m1party.api.RoleChanged event = new lk.coopfed.knoweb.m1party.api.RoleChanged(
                ROLE, ENTITY, 2, "ACTIVE", false, "AMENDED", List.of(), List.of("prt.location.view"));

        transactions.inOwnTransaction(central, () -> {
            fanOut.onRoleChanged(mapper.valueToTree(event), central);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("tables").path("operator").path("upserts").toString())
                .contains(CASHIER.toString());
        assertThat(nextJson.path("tables").path("operator").path("upserts").toString())
                .doesNotContain("prt.location.view");
    }

    /**
     * M1M2M3M5-26: the Federation amends a template a society assigned to its cashier. The event
     * fans out in the Federation's own scope, where security.user_role shows no society's
     * assignment; security.role_holder_shops (m1security V0021) finds the holder, and the
     * society's till gets the urgent operator row.
     */
    @Test
    void templateAmendedByTheFederationReachesTheSocietysTill() throws Exception {
        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        holdTheTemplateAtTheShop();
        superuserJdbc().update("delete from security.role_permission where role_id = ?", TEMPLATE);
        lk.coopfed.knoweb.m1party.api.RoleChanged event = new lk.coopfed.knoweb.m1party.api.RoleChanged(
                TEMPLATE, null, 2, "ACTIVE", true, "AMENDED", List.of(), List.of("prt.location.view"));

        transactions.inOwnTransaction(federation, () -> {
            fanOut.onRoleChanged(mapper.valueToTree(event), federation);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("urgent").asBoolean())
                .as("a permission removed from a template is urgent for the society's till")
                .isTrue();
        assertThat(nextJson.path("tables").path("operator").path("upserts").toString())
                .contains(CASHIER.toString());
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.change_log where owner_entity_id = ? and location_id = ?"
                                        + " and table_name = 'operator' and row_id = ? and urgent",
                                Integer.class,
                                ENTITY,
                                SHOP,
                                CASHIER))
                .isEqualTo(1);
    }

    /**
     * security.role_holder_shops answers the role's manager only: OWN and entity-wide, the
     * Federation for a template, the owner for its own role. Every other class, the Federation at a
     * location, a society asking about the template and another entity asking about the society's
     * role get no row.
     */
    @Test
    void theHolderLookupAnswersOnlyTheRolesManager() {
        holdTheTemplateAtTheShop();
        String holders = "select shop_entity_id::text || '/' || shop_location_id::text || '/' || holder_user_id::text"
                + " from security.role_holder_shops(?)";
        String expected = ENTITY + "/" + SHOP + "/" + CASHIER;

        assertThat(transactions.inOwnTransaction(
                        federation, () -> appJdbc.queryForList(holders, String.class, TEMPLATE)))
                .containsExactly(expected);
        assertThat(transactions.inOwnTransaction(central, () -> appJdbc.queryForList(holders, String.class, ROLE)))
                .containsExactly(expected);

        assertThat(transactions.inOwnTransaction(central, () -> appJdbc.queryForList(holders, String.class, TEMPLATE)))
                .as("a society does not manage the Federation's template")
                .isEmpty();
        assertThat(transactions.inOwnTransaction(
                        SystemScope.own(TEST_FEDERATION, SHOP),
                        () -> appJdbc.queryForList(holders, String.class, TEMPLATE)))
                .as("the Federation at a location")
                .isEmpty();
        assertThat(transactions.inOwnTransaction(
                        SystemScope.own(OTHER_ENTITY, null), () -> appJdbc.queryForList(holders, String.class, ROLE)))
                .as("another entity asking about the society's own role")
                .isEmpty();

        for (String policyClass : List.of("FEDERATION_VIEW", "EXTERNAL_TIMEBOXED", "PARTY", "NONE", "")) {
            for (UUID role : List.of(TEMPLATE, ROLE)) {
                List<String> rows = new TransactionTemplate(transactionManager).execute(status -> {
                    appJdbc.queryForObject(
                            "select set_config('app.scope_entity_id', ?, true)",
                            String.class,
                            (TEMPLATE.equals(role) ? TEST_FEDERATION : ENTITY).toString());
                    appJdbc.queryForObject("select set_config('app.scope_location_id', '', true)", String.class);
                    appJdbc.queryForObject("select set_config('app.scope_class', ?, true)", String.class, policyClass);
                    appJdbc.queryForObject(
                            "select set_config('app.granted_entities', ?, true)", String.class, "{" + ENTITY + "}");
                    try {
                        return appJdbc.queryForList(holders, String.class, role);
                    } finally {
                        status.setRollbackOnly();
                    }
                });
                assertThat(rows)
                        .as("class '" + policyClass + "' asking about " + role)
                        .isEmpty();
            }
        }
    }

    private void holdTheTemplateAtTheShop() {
        JdbcTemplate db = superuserJdbc();
        db.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template) values (?, null,"
                        + " 'Snapshot template', true)",
                TEMPLATE);
        db.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'prt.location.view')",
                TEMPLATE);
        db.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                CASHIER,
                TEMPLATE,
                ENTITY,
                SHOP);
    }

    @Test
    void shopUpdatePropagatesToLocationTable() throws Exception {
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new lk.coopfed.knoweb.kernel.api.ChangeLog.Target(ENTITY, SHOP)),
                    List.of(lk.coopfed.knoweb.kernel.api.ChangeLog.Change.upsert("location", SHOP)),
                    null,
                    false,
                    central);
            return null;
        });

        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        superuserJdbc().update("update party.location set language = 'si' where location_id = ?", SHOP);
        lk.coopfed.knoweb.m1party.api.LocationUpdated event =
                new lk.coopfed.knoweb.m1party.api.LocationUpdated(SHOP, ENTITY, "si", List.of(), "A", true);

        transactions.inOwnTransaction(central, () -> {
            fanOut.onShopEvent(mapper.valueToTree(event), central);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("tables").path("location").path("upserts").toString())
                .contains("\"language\":\"si\"");
    }

    @Test
    void tillPositionRegisteredPropagatesToTillPositionTable() throws Exception {
        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        UUID positionId = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into party.till_position (till_position_id, location_id, position_no, owner_entity_id) values (?, ?, 1, ?)",
                        positionId,
                        SHOP,
                        ENTITY);
        lk.coopfed.knoweb.m1party.api.TillPositionRegistered event =
                new lk.coopfed.knoweb.m1party.api.TillPositionRegistered(positionId, SHOP, ENTITY, 1, List.of());

        transactions.inOwnTransaction(central, () -> {
            fanOut.onTillPositionEvent(mapper.valueToTree(event), central);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("tables").path("till_position").path("upserts").toString())
                .contains(positionId.toString());
    }

    @Test
    void operatorRoleAssignmentPropagatesToShop() throws Exception {
        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        UUID newUser = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status, pin_hash) values (?, ?, 'newcashier', 'New', 'TILL', 'ACTIVE', 'hash')",
                        newUser,
                        ENTITY);
        superuserJdbc()
                .update(
                        "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                        newUser,
                        ROLE,
                        ENTITY,
                        SHOP);
        lk.coopfed.knoweb.m1party.api.RoleAssigned event =
                new lk.coopfed.knoweb.m1party.api.RoleAssigned(ROLE, newUser, ENTITY, SHOP);

        transactions.inOwnTransaction(central, () -> {
            fanOut.onRoleAssigned(mapper.valueToTree(event), central);
            return null;
        });

        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("tables").path("operator").path("upserts").toString())
                .contains(newUser.toString());
    }

    @Test
    void crossLocationIsolationRespectsScope() throws Exception {
        long tillVersion = mapper.valueToTree(transactions.inOwnTransaction(
                        till, () -> (Object) snapshotBuilder.build(till, 0, java.time.Duration.ofDays(30))))
                .path("version")
                .asLong();

        UUID otherShop = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status) values (?, ?, 'M1S2', 'SHOP', 'Other shop', 'en', 'ACTIVE')",
                        otherShop,
                        ENTITY);

        UUID newUser = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status, pin_hash) values (?, ?, 'othercashier', 'Other', 'TILL', 'ACTIVE', 'hash')",
                        newUser,
                        ENTITY);
        superuserJdbc()
                .update(
                        "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                        newUser,
                        ROLE,
                        ENTITY,
                        otherShop);
        lk.coopfed.knoweb.m1party.api.RoleAssigned event =
                new lk.coopfed.knoweb.m1party.api.RoleAssigned(ROLE, newUser, ENTITY, otherShop);

        transactions.inOwnTransaction(central, () -> {
            fanOut.onRoleAssigned(mapper.valueToTree(event), central);
            return null;
        });

        // The till at SHOP should NOT see the operator assigned to otherShop
        com.fasterxml.jackson.databind.JsonNode nextJson = mapper.valueToTree(transactions.inOwnTransaction(
                till, () -> (Object) snapshotBuilder.build(till, tillVersion, java.time.Duration.ofDays(30))));
        assertThat(nextJson.path("tables").path("operator").isMissingNode()).isTrue();

        superuserJdbc().update("delete from security.user_role where user_id = ?", newUser);
        superuserJdbc().update("delete from security.app_user where user_id = ?", newUser);
        superuserJdbc().update("delete from party.location where location_id = ?", otherShop);
    }
}
