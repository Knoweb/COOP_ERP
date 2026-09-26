package lk.coopfed.knoweb.m1party.internal.snapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The operators of a shop in its till's snapshot (21A section 7.3, OperatorSnapshotContributor;
 * doc 32 section 5.1, "operators assigned to the location: user id, display name, PIN hash,
 * resolved offline permissions with catalogue version; signed"). The kernel signs the whole
 * snapshot's manifest, which covers these rows (19A section 3, the till snapshot signer).
 *
 * <p>An operator is an ACTIVE user of kind TILL or BOTH with a PIN, holding an assignment of an
 * ACTIVE role at this shop or entity-wide at the shop's entity. Their permissions are the
 * offline-allowed ones of those roles (the till checks nothing else offline), and {@code rv} is
 * the permission catalogue version they were resolved against. The PIN hash is Argon2id and is
 * verified on the till (doc 19 section 2); it never travels anywhere but here.
 *
 * <p>Snapshot table {@code operator}, row id = user id. A user who stops qualifying (deactivated,
 * assignment revoked) is not returned, and the kernel sends the till a tombstone.
 */
@Component
class OperatorSnapshotContributor implements SnapshotContributor {

    static final String OPERATOR = "operator";

    private final NamedParameterJdbcTemplate jdbc;

    OperatorSnapshotContributor(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Set<String> tables() {
        return Set.of(OPERATOR);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return Map.of();
        }
        return operators(shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        return operators(shop, null);
    }

    /** The shop's operators; {@code userIds} null for all of them. */
    private Map<UUID, Map<String, Object>> operators(Shop shop, Collection<UUID> userIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entity", shop.ownerEntityId())
                .addValue("location", shop.locationId())
                .addValue("ids", userIds);
        String onlyIds = userIds == null ? "" : " and u.user_id in (:ids)";

        // An assignment that applies at this shop: at the shop itself, or entity-wide.
        String appliesHere =
                """
                ur.scope_entity_id = :entity
                and (ur.scope_location_id is null or ur.scope_location_id = :location)
                and r.status = 'ACTIVE'
                """;

        Integer rv = jdbc.queryForObject(
                "select coalesce(max(rv), 0) from security.permission_catalogue_version", params, Integer.class);

        Map<UUID, Map<String, Object>> operators = new LinkedHashMap<>();
        jdbc.query(
                """
                select u.user_id, u.display_name, u.language, u.pin_hash
                  from security.app_user u
                 where u.status = 'ACTIVE'
                   and u.user_kind in ('TILL', 'BOTH')
                   and u.pin_hash is not null
                   and exists (select 1
                                 from security.user_role ur
                                 join security.role r on r.role_id = ur.role_id
                                where ur.user_id = u.user_id and
                               """
                        + appliesHere
                        + ")"
                        + onlyIds,
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("display_name", rs.getString("display_name"));
                    row.put("language", rs.getString("language"));
                    row.put("pin_hash", rs.getString("pin_hash"));
                    row.put("permissions", new TreeSet<String>());
                    row.put("rv", rv);
                    operators.put(rs.getObject("user_id", UUID.class), row);
                });
        if (operators.isEmpty()) {
            return operators;
        }

        jdbc.query(
                """
                select distinct ur.user_id, p.permission_code
                  from security.user_role ur
                  join security.role r on r.role_id = ur.role_id
                  join security.role_permission rp on rp.role_id = r.role_id
                  join security.permission p on p.permission_code = rp.permission_code
                 where p.offline_allowed
                   and ur.user_id in (:operators)
                   and
                """
                        + appliesHere,
                new MapSqlParameterSource(params.getValues()).addValue("operators", operators.keySet()),
                rs -> {
                    @SuppressWarnings("unchecked")
                    Set<String> permissions = (Set<String>)
                            operators.get(rs.getObject("user_id", UUID.class)).get("permissions");
                    permissions.add(rs.getString("permission_code"));
                });

        // The permissions travel as a sorted list: the manifest hash is taken over them.
        operators.values().forEach(row -> row.put("permissions", new ArrayList<>((Set<?>) row.get("permissions"))));
        return operators;
    }
}
