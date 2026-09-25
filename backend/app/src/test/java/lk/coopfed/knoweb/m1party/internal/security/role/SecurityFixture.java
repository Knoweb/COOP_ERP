package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The rows the role tests arrange as the superuser, and take away again: entities, locations,
 * users, roles and assignments that exist before the command under test runs. Everything it
 * makes, and every role the commands under test make, is deleted by {@link #clean()},
 * templates included, because M1SeedLoaderTest counts the templates and the pairs of the whole
 * table.
 *
 * <p>The Federation is special: the table admits one (party.one_federation). The fixture uses
 * the one that exists, and makes (and later removes) one only when there is none. A role a test
 * makes for the Federation is named with {@link #NAME_PREFIX} so that it can be found again.
 */
final class SecurityFixture {

    /** Every role and template a test names starts with this, so that clean() finds it. */
    static final String NAME_PREFIX = "M1-08 test ";

    private final JdbcTemplate admin;
    private final List<UUID> entities = new ArrayList<>();
    private final List<UUID> locations = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private final List<String> permissions = new ArrayList<>();
    private UUID madeFederation;

    SecurityFixture(JdbcTemplate admin) {
        this.admin = admin;
    }

    /** The Federation entity: the existing one, or one made here. */
    UUID federation() {
        List<UUID> found =
                admin.queryForList("select entity_id from party.entity where entity_type = 'FEDERATION'", UUID.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        madeFederation = Ids.next();
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, 'FEDERATION', ?)",
                madeFederation,
                code("F"),
                "Federation (M1-08 test)");
        return madeFederation;
    }

    UUID mpcs(String name) {
        UUID id = Ids.next();
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, 'MPCS', ?)",
                id,
                code("M"),
                name);
        entities.add(id);
        return id;
    }

    UUID shop(UUID owner) {
        UUID id = Ids.next();
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                        + " values (?, ?, ?, 'SHOP', ?)",
                id,
                owner,
                code("S"),
                "Shop " + id);
        locations.add(id);
        return id;
    }

    UUID user(UUID homeEntity) {
        return user(homeEntity, "ACTIVE");
    }

    UUID user(UUID homeEntity, String status) {
        UUID id = Ids.next();
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, ?, 'BACK_OFFICE', ?)",
                id,
                homeEntity,
                "u-" + id,
                "User " + id,
                status);
        users.add(id);
        return id;
    }

    /** A role written directly, bypassing every guard: the state a test starts from. Owner null: a template. */
    UUID roleOfClass(UUID owner, String roleClass, String... permissions) {
        UUID id = Ids.next();
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, ?, ?, ?, 'ACTIVE')",
                id,
                owner,
                NAME_PREFIX + id,
                owner == null,
                roleClass);
        for (String permission : permissions) {
            admin.update(
                    "insert into security.role_permission (role_id, permission_code) values (?, ?)", id, permission);
        }
        return id;
    }

    UUID role(UUID owner, String... permissions) {
        return roleOfClass(owner, "OWN", permissions);
    }

    void assign(UUID user, UUID role, UUID entity, UUID location) {
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, ?)",
                user,
                role,
                entity,
                location);
    }

    /** A pair written directly; owner null makes a federation default, which clean() removes too. */
    UUID pair(UUID owner, String a, String b, String mode) {
        List<String> ordered = RoleRules.ordered(a, b);
        UUID id = Ids.next();
        admin.update(
                "insert into security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)"
                        + " values (?, ?, ?, ?, ?)",
                id,
                ordered.get(0),
                ordered.get(1),
                mode,
                owner);
        pairs.add(id);
        return id;
    }

    /** A permission only the tests know, with a limits schema; clean() removes it. */
    void permission(String code, String scope, String limitsSchema) {
        admin.update(
                "insert into security.permission (permission_code, module, description_en, scope, limits_schema)"
                        + " values (?, 'm1party', 'M1-08 test permission', ?, cast(? as jsonb))",
                code,
                scope,
                limitsSchema);
        permissions.add(code);
    }

    List<String> permissionsOf(UUID role) {
        return admin.queryForList(
                "select permission_code from security.role_permission where role_id = ? order by 1",
                String.class,
                role);
    }

    /** Removes everything this fixture and the commands under test made. */
    void clean() {
        List<UUID> owners = new ArrayList<>(entities);
        if (madeFederation != null) {
            owners.add(madeFederation);
        }
        List<UUID> roles = new ArrayList<>(admin.queryForList(
                "select role_id from security.role where name_en like ?", UUID.class, NAME_PREFIX + "%"));
        if (!owners.isEmpty()) {
            roles.addAll(admin.queryForList(
                    "select role_id from security.role where owner_entity_id in " + in(owners), UUID.class));
        }
        if (!users.isEmpty()) {
            admin.execute("delete from security.user_role where user_id in " + in(users));
        }
        if (!roles.isEmpty()) {
            admin.execute("delete from security.user_role where role_id in " + in(roles));
            admin.execute("update security.role set template_role_id = null where template_role_id in " + in(roles));
            admin.execute("delete from security.role_permission where role_id in " + in(roles));
            admin.execute("delete from security.role where role_id in " + in(roles));
        }
        if (!owners.isEmpty()) {
            admin.execute("delete from security.user_role where scope_entity_id in " + in(owners));
            admin.execute("delete from security.sod_pair where owner_entity_id in " + in(owners));
        }
        if (!users.isEmpty()) {
            admin.execute("delete from security.app_user where user_id in " + in(users));
        }
        if (!locations.isEmpty()) {
            admin.execute("delete from party.location where location_id in " + in(locations));
        }
        if (!owners.isEmpty()) {
            admin.execute("delete from party.entity_party_directory where entity_id in " + in(owners));
            admin.execute("delete from party.federation_identity where entity_id in " + in(owners));
            admin.execute("delete from party.entity where entity_id in " + in(owners));
        }
        if (!pairs.isEmpty()) {
            admin.execute("delete from security.sod_pair where sod_pair_id in " + in(pairs));
        }
        for (String code : permissions) {
            admin.update("delete from security.role_permission where permission_code = ?", code);
            admin.update("delete from security.permission where permission_code = ?", code);
        }
        entities.clear();
        locations.clear();
        users.clear();
        pairs.clear();
        permissions.clear();
        madeFederation = null;
    }

    private static String code(String prefix) {
        return prefix
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static String in(Collection<UUID> ids) {
        return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", ", "(", ")"));
    }
}
