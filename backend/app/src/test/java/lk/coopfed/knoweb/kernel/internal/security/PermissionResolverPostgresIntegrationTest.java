package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Sod;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The resolver and the separation-of-duties helper over M1's tables (19A section 3, "Tests"):
 * the union over roles never yields a permission absent from every role; an entity-wide
 * assignment applies at every location and a location assignment there alone; a read-only
 * class resolves to nothing; the cache empties on the role and user events; a permission
 * flagged for MFA says so; an INSTANCE pair refuses the same person and admits another.
 */
class PermissionResolverPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a300-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190a300-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190a300-0000-7000-8000-000000000101");
    private static final UUID OTHER_SHOP = UUID.fromString("0190a300-0000-7000-8000-000000000102");
    private static final UUID ADMIN = UUID.fromString("0190a300-0000-7000-8000-000000000010");
    private static final UUID CASHIER = UUID.fromString("0190a300-0000-7000-8000-000000000011");
    private static final UUID ENTITY_ROLE = UUID.fromString("0190a300-0000-7000-8000-000000000201");
    private static final UUID SHOP_ROLE = UUID.fromString("0190a300-0000-7000-8000-000000000202");

    @Autowired
    PermissionResolver resolver;

    @Autowired
    JdbcPermissionResolver jdbcResolver;

    @Autowired
    PermissionCacheInvalidator invalidator;

    @Autowired
    Sod sod;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void leaveTheTablesAsTheyWere() {
        // The seed loader test counts roles and pairs: what this test added goes again.
        clean(superuserJdbc());
    }

    private static void clean(JdbcTemplate admin) {
        admin.execute(
                "delete from security.user_role where scope_entity_id in ('" + ENTITY + "', '" + OTHER_ENTITY + "')");
        admin.execute(
                "delete from security.role_permission where role_id in ('" + ENTITY_ROLE + "', '" + SHOP_ROLE + "')");
        admin.execute("delete from security.role where role_id in ('" + ENTITY_ROLE + "', '" + SHOP_ROLE + "')");
        admin.execute("delete from security.app_user where user_id in ('" + ADMIN + "', '" + CASHIER + "')");
        admin.execute("delete from security.sod_pair where sod_pair_id = '0190a300-0000-7000-8000-000000000301'");
    }

    @BeforeEach
    void usersAndRoles() {
        JdbcTemplate admin = superuserJdbc();
        clean(admin);
        jdbcResolver.invalidateAll();

        for (UUID user : List.of(ADMIN, CASHIER)) {
            admin.update(
                    "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                            + " values (?, ?, ?, ?, 'BACK_OFFICE', 'ACTIVE')",
                    user,
                    ENTITY,
                    "u-" + user,
                    "User " + user);
        }
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Entity manager', false, 'OWN', 'ACTIVE')",
                ENTITY_ROLE,
                ENTITY);
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Shop clerk', false, 'OWN', 'ACTIVE')",
                SHOP_ROLE,
                ENTITY);
        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'prt.location.manage')",
                ENTITY_ROLE);
        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'prt.location.view')",
                ENTITY_ROLE);
        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'prt.location.view')",
                SHOP_ROLE);
        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'sys.device.view')",
                SHOP_ROLE);

        // The admin holds the entity role entity-wide; the cashier holds the shop role at one shop.
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, null)",
                ADMIN,
                ENTITY_ROLE,
                ENTITY);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                CASHIER,
                SHOP_ROLE,
                ENTITY,
                SHOP);
        admin.update(
                "insert into security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)"
                        + " values ('0190a300-0000-7000-8000-000000000301', 'prt.location.manage', 'prt.location.view', 'INSTANCE', null)");
    }

    @Test
    void theUnionOverRolesInTheScopeAndNothingMore() {
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.resolve(scope(ADMIN, ENTITY, null))))
                .containsExactlyInAnyOrder("prt.location.manage", "prt.location.view");
        assertThat(inScope(
                        ADMIN, ENTITY, null, () -> resolver.allows(scope(ADMIN, ENTITY, null), "prt.location.manage")))
                .isTrue();
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.allows(scope(ADMIN, ENTITY, null), "sys.device.view")))
                .isFalse();
        // Another entity: nothing, even for the admin.
        assertThat(inScope(ADMIN, OTHER_ENTITY, null, () -> resolver.resolve(scope(ADMIN, OTHER_ENTITY, null))))
                .isEmpty();
    }

    @Test
    void anEntityAssignmentAppliesAtEveryLocationAndALocationAssignmentThereAlone() {
        assertThat(inScope(ADMIN, ENTITY, OTHER_SHOP, () -> resolver.resolve(scope(ADMIN, ENTITY, OTHER_SHOP))))
                .contains("prt.location.manage");
        assertThat(inScope(CASHIER, ENTITY, SHOP, () -> resolver.resolve(scope(CASHIER, ENTITY, SHOP))))
                .containsExactlyInAnyOrder("prt.location.view", "sys.device.view");
        assertThat(inScope(CASHIER, ENTITY, OTHER_SHOP, () -> resolver.resolve(scope(CASHIER, ENTITY, OTHER_SHOP))))
                .isEmpty();
        // Entity-wide, the shop grant does not reach: a cashier is not an entity officer.
        assertThat(inScope(CASHIER, ENTITY, null, () -> resolver.resolve(scope(CASHIER, ENTITY, null))))
                .isEmpty();
    }

    @Test
    void aReadOnlyClassResolvesToNothing() {
        Scope active = new Scope(ENTITY, null);
        ScopeContext fedView = new ScopeContext(
                ADMIN,
                null,
                ENTITY,
                List.of(active),
                active,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
        assertThat(resolver.resolve(fedView)).isEmpty();
    }

    @Test
    void theCacheEmptiesOnTheRoleEvents() throws Exception {
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.resolve(scope(ADMIN, ENTITY, null))))
                .hasSize(2);

        superuserJdbc()
                .update(
                        "delete from security.role_permission where role_id = ? and permission_code = 'prt.location.manage'",
                        ENTITY_ROLE);
        // Still cached.
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.resolve(scope(ADMIN, ENTITY, null))))
                .hasSize(2);

        invalidator.onChange(
                new ObjectMapper().readTree("{\"roleId\":\"" + ENTITY_ROLE + "\"}"), scope(ADMIN, ENTITY, null));
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.resolve(scope(ADMIN, ENTITY, null))))
                .containsExactly("prt.location.view");
    }

    @Test
    void theCatalogueSaysWhichPermissionsNeedASecondFactor() {
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.requiresMfa("gov.entity.activate")))
                .isTrue();
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.requiresMfa("gov.entity.view")))
                .isFalse();
        assertThat(inScope(ADMIN, ENTITY, null, () -> resolver.requiresMfa("no.such.permission")))
                .isFalse();
    }

    @Test
    void anInstancePairRefusesTheSamePersonAndAdmitsAnother() {
        assertThatThrownBy(() -> inScope(ADMIN, ENTITY, null, () -> {
                    sod.assertDistinct(scope(ADMIN, ENTITY, null), "prt.location.manage", "prt.location.view", ADMIN);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("sod.same_person");

        inScope(ADMIN, ENTITY, null, () -> {
            sod.assertDistinct(scope(ADMIN, ENTITY, null), "prt.location.manage", "prt.location.view", CASHIER);
            // A pair nobody registered: no rule, no refusal.
            sod.assertDistinct(scope(ADMIN, ENTITY, null), "sys.device.view", "prt.location.view", ADMIN);
            return null;
        });
    }

    private static ScopeContext scope(UUID user, UUID entity, UUID location) {
        Scope active = new Scope(entity, location);
        return new ScopeContext(
                user,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    private <T> T inScope(UUID user, UUID entity, UUID location, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    user.toString(),
                    Ids.next().toString(),
                    entity.toString(),
                    location == null ? "" : location.toString());
            return work.get();
        });
    }
}
