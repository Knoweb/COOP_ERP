package lk.coopfed.knoweb.m1party.internal.queries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.SecurityQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles, their template diff, pairs and assignments, read under the caller's row-level
 * security (m1security V0001, V0008, V0010): the scope entity's roles and the federation
 * templates, and nothing of another entity. No query here filters by tenant.
 */
@Service
@Transactional(readOnly = true)
class SecurityQueriesImpl implements SecurityQueries {

    private static final TypeReference<Map<String, Object>> LIMITS = new TypeReference<>() {};

    private static final String ROLE_SELECT =
            """
            select r.role_id, r.owner_entity_id, r.name_en, r.name_si, r.name_ta, r.is_template, r.role_class,
                   r.template_role_id, r.template_version_seen, r.version, r.status,
                   t.version as template_version
              from security.role r
              left join security.role t on t.role_id = r.template_role_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    SecurityQueriesImpl(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private record RoleRow(
            UUID roleId,
            UUID ownerEntityId,
            String nameEn,
            String nameSi,
            String nameTa,
            boolean template,
            String roleClass,
            UUID templateRoleId,
            Integer templateVersionSeen,
            Integer templateVersion,
            int version,
            String status) {}

    @Override
    public List<RoleView> listRoles(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        List<RoleRow> rows = jdbc.query(
                ROLE_SELECT + " order by r.is_template, lower(r.name_en), r.role_id", (rs, i) -> roleRow(rs));
        List<RoleView> views = new ArrayList<>();
        for (RoleRow row : rows) {
            views.add(view(row));
        }
        return views;
    }

    @Override
    public Optional<RoleView> getRole(UUID roleId, ScopeContext scope) {
        return row(roleId, scope).map(this::view);
    }

    @Override
    public Optional<RoleDiff> getRoleDiff(UUID roleId, ScopeContext scope) {
        Optional<RoleRow> found = row(roleId, scope);
        if (found.isEmpty()
                || found.get().templateRoleId() == null
                || found.get().templateVersion() == null) {
            return Optional.empty();
        }
        RoleRow role = found.get();
        Map<String, Map<String, Object>> mine = permissions(role.roleId());
        Map<String, Map<String, Object>> template = permissions(role.templateRoleId());

        List<String> onlyInTemplate = template.keySet().stream()
                .filter(code -> !mine.containsKey(code))
                .sorted()
                .toList();
        List<String> onlyInRole = mine.keySet().stream()
                .filter(code -> !template.containsKey(code))
                .sorted()
                .toList();
        List<String> limitsDiffer = mine.keySet().stream()
                .filter(template::containsKey)
                .filter(code -> !Objects.equals(mine.get(code), template.get(code)))
                .sorted()
                .toList();
        return Optional.of(new RoleDiff(
                role.roleId(),
                role.templateRoleId(),
                role.templateVersionSeen(),
                role.templateVersion(),
                updated(role),
                onlyInTemplate,
                onlyInRole,
                limitsDiffer));
    }

    @Override
    public List<SodPairView> listSodPairs(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        return jdbc.query(
                """
                select sod_pair_id, permission_a, permission_b, mode, owner_entity_id
                  from security.sod_pair
                 order by permission_a, permission_b, owner_entity_id nulls first
                """,
                (rs, i) -> new SodPairView(
                        rs.getObject("sod_pair_id", UUID.class),
                        rs.getString("permission_a"),
                        rs.getString("permission_b"),
                        rs.getString("mode"),
                        rs.getObject("owner_entity_id", UUID.class)));
    }

    @Override
    public List<AssignmentView> listAssignments(UUID userId, ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        return jdbc.query(
                """
                select user_id, role_id, scope_entity_id, scope_location_id
                  from security.user_role
                 where cast(? as uuid) is null or user_id = ?
                 order by user_id, role_id, scope_location_id nulls first
                """,
                (rs, i) -> new AssignmentView(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("role_id", UUID.class),
                        rs.getObject("scope_entity_id", UUID.class),
                        rs.getObject("scope_location_id", UUID.class)),
                userId,
                userId);
    }

    private Optional<RoleRow> row(UUID roleId, ScopeContext scope) {
        if (roleId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return jdbc.query(ROLE_SELECT + " where r.role_id = ?", (rs, i) -> roleRow(rs), roleId).stream()
                .findFirst();
    }

    private static RoleRow roleRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoleRow(
                rs.getObject("role_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("name_en"),
                rs.getString("name_si"),
                rs.getString("name_ta"),
                rs.getBoolean("is_template"),
                rs.getString("role_class"),
                rs.getObject("template_role_id", UUID.class),
                (Integer) rs.getObject("template_version_seen"),
                (Integer) rs.getObject("template_version"),
                rs.getInt("version"),
                rs.getString("status"));
    }

    private RoleView view(RoleRow row) {
        List<RolePermissionView> permissions = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> p :
                permissions(row.roleId()).entrySet()) {
            permissions.add(new RolePermissionView(p.getKey(), p.getValue()));
        }
        return new RoleView(
                row.roleId(),
                row.ownerEntityId(),
                row.nameEn(),
                row.nameSi(),
                row.nameTa(),
                row.template(),
                row.roleClass(),
                row.templateRoleId(),
                row.templateVersionSeen(),
                row.templateVersion(),
                updated(row),
                row.version(),
                row.status(),
                permissions);
    }

    /** The "template updated" marker: the template has a version the clone has not taken. */
    private static boolean updated(RoleRow row) {
        return row.templateVersion() != null
                && (row.templateVersionSeen() == null || row.templateVersion() > row.templateVersionSeen());
    }

    private Map<String, Map<String, Object>> permissions(UUID roleId) {
        Map<String, Map<String, Object>> found = new TreeMap<>();
        Map<String, String> raw = new HashMap<>();
        jdbc.query(
                "select permission_code, limits::text as limits from security.role_permission where role_id = ?",
                rs -> {
                    raw.put(rs.getString("permission_code"), rs.getString("limits"));
                },
                roleId);
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            found.put(entry.getKey(), limits(entry.getValue()));
        }
        return found;
    }

    private Map<String, Object> limits(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, LIMITS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("security.role_permission.limits is not a JSON object: " + json, e);
        }
    }
}
