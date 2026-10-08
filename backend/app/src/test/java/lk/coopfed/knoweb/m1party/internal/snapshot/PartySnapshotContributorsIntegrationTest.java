package lk.coopfed.knoweb.m1party.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M1's snapshot contributors (21A section 7.3) in the scope of a till of the shop, as the
 * kernel's snapshot builder calls them: the shop, its positions with the primary till, and the
 * operators who may sign in there with their offline permissions and the catalogue version.
 */
class PartySnapshotContributorsIntegrationTest extends PostgresIntegrationTest {

    static final UUID ENTITY = UUID.fromString("0190a810-0000-7000-8000-000000000001");
    static final UUID SHOP = UUID.fromString("0190a810-0000-7000-8000-000000000101");
    static final UUID OTHER_SHOP = UUID.fromString("0190a810-0000-7000-8000-000000000102");
    static final UUID PRIMARY = UUID.fromString("0190a810-0000-7000-8000-000000000201");
    static final UUID SECOND = UUID.fromString("0190a810-0000-7000-8000-000000000202");
    static final UUID CASHIER = UUID.fromString("0190a810-0000-7000-8000-000000000401");
    static final UUID SUPERVISOR = UUID.fromString("0190a810-0000-7000-8000-000000000402");
    static final UUID ELSEWHERE = UUID.fromString("0190a810-0000-7000-8000-000000000403");
    static final UUID GONE = UUID.fromString("0190a810-0000-7000-8000-000000000404");
    static final UUID BACK_OFFICE = UUID.fromString("0190a810-0000-7000-8000-000000000405");
    static final UUID ROLE = UUID.fromString("0190a810-0000-7000-8000-000000000501");

    /** A permission the seed marks offline_allowed, and one it does not. */
    static final String OFFLINE = "prt.location.view";

    static final String ONLINE_ONLY = "prt.location.register";

    @Autowired
    ShopSnapshotContributor shop;

    @Autowired
    OperatorSnapshotContributor operators;

    @Autowired
    SystemScope transactions;

    private final Shop theShop = new Shop(ENTITY, SHOP);

    /** The scope of a till of the shop: the entity's OWN scope at the location. */
    private final ScopeContext till = SystemScope.own(ENTITY, SHOP);

    @BeforeEach
    void aShopWithPositionsAndPeople() {
        JdbcTemplate db = superuserJdbc();
        forget(db);
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en,
                                            language, trading_hours, status)
                values (?, ?, 'M1S1', 'SHOP', 'Snapshot shop', 'si', '{"mon":"08:00-20:00"}', 'ACTIVE'),
                       (?, ?, 'M1S2', 'SHOP', 'Other shop', 'en', null, 'ACTIVE')
                """,
                SHOP,
                ENTITY,
                OTHER_SHOP,
                ENTITY);
        db.update(
                "insert into party.till_position (till_position_id, location_id, position_no, owner_entity_id) values (?, ?, 1, ?), (?, ?, 2, ?)",
                PRIMARY,
                SHOP,
                ENTITY,
                SECOND,
                SHOP,
                ENTITY);
        db.update("update party.location set primary_till_position_id = ? where location_id = ?", PRIMARY, SHOP);

        user(db, CASHIER, "m1snap-cashier", "TILL", "ACTIVE");
        user(db, SUPERVISOR, "m1snap-supervisor", "BOTH", "ACTIVE");
        user(db, ELSEWHERE, "m1snap-elsewhere", "TILL", "ACTIVE");
        user(db, GONE, "m1snap-gone", "TILL", "DEACTIVATED");
        user(db, BACK_OFFICE, "m1snap-office", "BACK_OFFICE", "ACTIVE");
        db.update(
                "insert into security.role (role_id, owner_entity_id, name_en) values (?, ?, 'Snapshot cashier')",
                ROLE,
                ENTITY);
        db.update(
                "insert into security.role_permission (role_id, permission_code) values (?, ?), (?, ?)",
                ROLE,
                OFFLINE,
                ROLE,
                ONLINE_ONLY);
        assign(db, CASHIER, ROLE, SHOP); // at the shop
        assign(db, SUPERVISOR, ROLE, null); // entity-wide
        assign(db, ELSEWHERE, ROLE, OTHER_SHOP); // at another shop only
        assign(db, GONE, ROLE, SHOP);
        assign(db, BACK_OFFICE, ROLE, SHOP);
    }

    @AfterEach
    void forgetAfterwards() {
        forget(superuserJdbc());
    }

    @Test
    void theShopRowCarriesWhatTheTillShows() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(till, () -> shop.allRows("location", theShop));

        assertThat(rows).containsOnlyKeys(SHOP);
        assertThat(rows.get(SHOP))
                .containsEntry("name_en", "Snapshot shop")
                .containsEntry("language", "si")
                .containsEntry("primary_till_position_id", PRIMARY.toString())
                .containsEntry("status", "ACTIVE");
        assertThat((String) rows.get(SHOP).get("trading_hours")).contains("08:00-20:00");
    }

    @Test
    void thePositionsOfTheShopWithThePrimaryTill() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(till, () -> shop.allRows("till_position", theShop));

        assertThat(rows).containsOnlyKeys(PRIMARY, SECOND);
        assertThat(rows.get(PRIMARY)).containsEntry("primary_till", true).containsEntry("position_no", 1);
        assertThat(rows.get(SECOND)).containsEntry("primary_till", false);
        assertThat(transactions.inScope(till, () -> shop.rows("till_position", theShop, List.of(SECOND))))
                .containsOnlyKeys(SECOND);
    }

    @Test
    void operatorRoundTrip_theTillPeopleOfTheShopWithTheirOfflinePermissionsOnly() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(till, () -> operators.allRows("operator", theShop));

        // Assigned at the shop and entity-wide; not at another shop only, not deactivated, not
        // back-office staff, who never sign in at a till.
        assertThat(rows).containsOnlyKeys(CASHIER, SUPERVISOR);
        Map<String, Object> cashier = rows.get(CASHIER);
        assertThat(cashier)
                .containsEntry("display_name", "m1snap-cashier")
                .containsEntry("pin_hash", "$argon2id$test")
                .containsEntry("permissions", List.of(OFFLINE));
        assertThat(cashier.get("rv")).isInstanceOf(Integer.class);
    }

    @Test
    void anOperatorWhoLeftIsNotReturnedSoTheTillGetsATombstone() {
        Map<UUID, Map<String, Object>> rows = transactions.inScope(
                till, () -> operators.rows("operator", theShop, List.of(CASHIER, GONE, ELSEWHERE)));

        assertThat(rows).containsOnlyKeys(CASHIER);
    }

    private static void user(JdbcTemplate db, UUID id, String name, String kind, String status) {
        db.update(
                """
                insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind,
                                               status, pin_hash)
                values (?, ?, ?, ?, ?, ?, '$argon2id$test')
                """,
                id,
                ENTITY,
                name,
                name,
                kind,
                status);
    }

    private static void assign(JdbcTemplate db, UUID user, UUID role, UUID location) {
        db.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                user,
                role,
                ENTITY,
                location);
    }

    private static void forget(JdbcTemplate db) {
        db.update("delete from security.user_role where role_id = ?", ROLE);
        db.update("delete from security.user_role where role_id = '01999a10-2c4e-7f1a-8b3d-5e6f70819a11'"); // Cashier
        db.update("delete from security.user_role where role_id = '01999a10-2c4e-7f1a-8b3d-5e6f70819a12'"); // Shop Supervisor
        db.update("delete from security.role_permission where role_id = ?", ROLE);
        db.update("delete from security.role where role_id = ?", ROLE);
        db.update("delete from security.app_user where username like 'm1snap-%'");
        db.update("update party.location set primary_till_position_id = null where owner_entity_id = ?", ENTITY);
        db.update("delete from party.till_position where owner_entity_id = ?", ENTITY);
        db.update("delete from party.location where owner_entity_id = ?", ENTITY);
    }

    @Test
    void cashierAndSupervisorTemplatesCarryTillPermissions() {
        JdbcTemplate db = superuserJdbc();
        
        // Seed cashier and supervisor templates if not present (since this test might run isolated without full DB seed)
        // Actually M1SeedLoader might have run, but just in case, ensure they exist
        db.update("INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status) VALUES ('01999a10-2c4e-7f1a-8b3d-5e6f70819a11', NULL, 'Cashier', true, 'OWN', 'ACTIVE') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status) VALUES ('01999a10-2c4e-7f1a-8b3d-5e6f70819a12', NULL, 'Shop Supervisor', true, 'OWN', 'ACTIVE') ON CONFLICT DO NOTHING");
        
        // Assign to new users
        UUID cashierUser = UUID.randomUUID();
        UUID supervisorUser = UUID.randomUUID();
        user(db, cashierUser, "m1snap-cashier-template", "TILL", "ACTIVE");
        user(db, supervisorUser, "m1snap-supervisor-template", "BOTH", "ACTIVE");
        
        assign(db, cashierUser, UUID.fromString("01999a10-2c4e-7f1a-8b3d-5e6f70819a11"), SHOP);
        assign(db, supervisorUser, UUID.fromString("01999a10-2c4e-7f1a-8b3d-5e6f70819a12"), SHOP);

        Map<UUID, Map<String, Object>> rows = transactions.inScope(till, () -> operators.rows("operator", theShop, List.of(cashierUser, supervisorUser)));

        assertThat(rows).containsOnlyKeys(cashierUser, supervisorUser);
        
        // Verify cashier has pos.receipt.issue
        Map<String, Object> c = rows.get(cashierUser);
        assertThat(c).containsEntry("display_name", "m1snap-cashier-template");
        @SuppressWarnings("unchecked")
        List<String> cPerms = (List<String>) c.get("permissions");
        assertThat(cPerms).contains("pos.receipt.issue", "pos.mrp.pick");

        // Verify supervisor has supervisor permissions
        Map<String, Object> s = rows.get(supervisorUser);
        assertThat(s).containsEntry("display_name", "m1snap-supervisor-template");
        @SuppressWarnings("unchecked")
        List<String> sPerms = (List<String>) s.get("permissions");
        assertThat(sPerms).contains("pos.receipt.issue", "pos.mrp.pick", "pos.receipt.void", "pos.refund.same_session", "pos.negative_stock.acknowledge", "pos.session.manage");

        // Cleanup
        db.update("delete from security.user_role where user_id in (?, ?)", cashierUser, supervisorUser);
        db.update("delete from security.app_user where user_id in (?, ?)", cashierUser, supervisorUser);
    }
}
