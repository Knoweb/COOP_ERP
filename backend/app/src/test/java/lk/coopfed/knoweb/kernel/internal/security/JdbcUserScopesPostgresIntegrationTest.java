package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link JdbcUserScopes} over M1's tables: the scopes are the active assignments, the grants
 * are the ACTIVE, current rows of {@code external_grant} keyed by the grantee, and the cache
 * empties on demand.
 */
class JdbcUserScopesPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a800-0000-7000-8000-000000000001");
    private static final UUID GRANTED = UUID.fromString("0190a800-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190a800-0000-7000-8000-000000000101");
    private static final UUID USER = UUID.fromString("0190a800-0000-7000-8000-000000000010");
    private static final UUID ROLE = UUID.fromString("0190a800-0000-7000-8000-000000000201");

    @Autowired
    JdbcUserScopes scopes;

    @BeforeEach
    void aUserWithAShopRoleAndAGrant() {
        JdbcTemplate admin = superuserJdbc();
        clean(admin);
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, 'Scopes test user', 'BACK_OFFICE', 'ACTIVE')",
                USER,
                ENTITY,
                "u-" + USER);
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Shop role', false, 'OWN', 'ACTIVE')",
                ROLE,
                ENTITY);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                USER,
                ROLE,
                ENTITY,
                SHOP);
        admin.update(
                "insert into security.external_grant (grant_id, grantee_user_id, scope_entity_ids, valid_from, valid_until, reason, status, owner_entity_id)"
                        + " values (?, ?, ARRAY[?]::uuid[], now() - interval '1 day', now() + interval '1 day', 'audit', 'ACTIVE', ?)",
                UUID.randomUUID(),
                USER,
                GRANTED,
                ENTITY);
        admin.update(
                "insert into security.external_grant (grant_id, grantee_user_id, scope_entity_ids, valid_from, valid_until, reason, status, owner_entity_id)"
                        + " values (?, ?, ARRAY[?]::uuid[], now() - interval '3 day', now() - interval '1 day', 'ended', 'EXPIRED', ?)",
                UUID.randomUUID(),
                USER,
                ENTITY,
                ENTITY);
        scopes.invalidateAll();
    }

    @AfterEach
    void leaveTheTablesAsTheyWere() {
        clean(superuserJdbc());
    }

    private static void clean(JdbcTemplate admin) {
        admin.execute("delete from security.external_grant where grantee_user_id = '" + USER + "'");
        admin.execute("delete from security.user_role where user_id = '" + USER + "'");
        admin.execute("delete from security.role where role_id = '" + ROLE + "'");
        admin.execute("delete from security.app_user where user_id = '" + USER + "'");
    }

    @Test
    void theScopesAreTheActiveAssignmentsAndTheGrantsTheCurrentActiveOnes() {
        assertThat(scopes.scopesOf(USER)).containsExactly(new Scope(ENTITY, SHOP));
        assertThat(scopes.grantsOf(USER)).containsExactly(GRANTED);
        assertThat(scopes.scopesOf(UUID.randomUUID())).isEmpty();
        assertThat(scopes.grantsOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theCacheHoldsUntilItIsEmptied() {
        assertThat(scopes.grantsOf(USER)).containsExactly(GRANTED);
        superuserJdbc().execute("delete from security.external_grant where grantee_user_id = '" + USER + "'");
        assertThat(scopes.grantsOf(USER)).containsExactly(GRANTED);
        scopes.invalidateUser(USER);
        assertThat(scopes.grantsOf(USER)).isEmpty();
    }
}
