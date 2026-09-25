package lk.coopfed.knoweb.m1party.internal.security.role;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What the role handlers read from the security schema, and nothing they write (only a command
 * handler writes, ArchitectureTests). Every read runs inside the handler's transaction, under
 * the caller's row-level security: a role, a user or a location of another entity is simply
 * not found, which the handlers turn into their own message ids.
 */
@Component
class SecurityRecords {

    /** The permission whose last holder an entity may not lose (doc 19 section 3.2, GUARDRAIL). */
    static final String USER_MANAGE = "gov.user.manage";

    private static final TypeReference<Map<String, Object>> LIMITS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    SecurityRecords(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    record RoleRow(
            UUID roleId,
            UUID ownerEntityId,
            String nameEn,
            boolean template,
            String roleClass,
            UUID templateRoleId,
            Integer templateVersionSeen,
            int version,
            String status) {

        boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    record CatalogueEntry(String code, String scope, JsonNode limitsSchema) {

        boolean isFederationScope() {
            return "FEDERATION".equals(scope);
        }
    }

    record UserRow(UUID userId, UUID homeEntityId, String status) {}

    record Assignment(UUID userId, UUID roleId, UUID scopeEntityId, UUID scopeLocationId) {}

    Optional<RoleRow> role(UUID roleId) {
        if (roleId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                        select role_id, owner_entity_id, name_en, is_template, role_class, template_role_id,
                               template_version_seen, version, status
                          from security.role
                         where role_id = ?
                        """,
                        (rs, i) -> new RoleRow(
                                rs.getObject("role_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getString("name_en"),
                                rs.getBoolean("is_template"),
                                rs.getString("role_class"),
                                rs.getObject("template_role_id", UUID.class),
                                (Integer) rs.getObject("template_version_seen"),
                                rs.getInt("version"),
                                rs.getString("status")),
                        roleId)
                .stream()
                .findFirst();
    }

    /** The permissions of a role, code to limits (null when none), sorted by code. */
    Map<String, Map<String, Object>> permissionsOf(UUID roleId) {
        Map<String, Map<String, Object>> found = new TreeMap<>();
        jdbc.query(
                "select permission_code, limits::text as limits from security.role_permission where role_id = ?",
                rs -> {
                    found.put(rs.getString("permission_code"), limits(rs.getString("limits")));
                },
                roleId);
        return found;
    }

    /** Whether a role of this owner (null: a template) already carries the name, ignoring case. */
    boolean nameTaken(UUID ownerEntityId, String nameEn) {
        Integer count = ownerEntityId == null
                ? jdbc.queryForObject(
                        "select count(*) from security.role where owner_entity_id is null and lower(name_en) = lower(?)",
                        Integer.class,
                        nameEn)
                : jdbc.queryForObject(
                        "select count(*) from security.role where owner_entity_id = ? and lower(name_en) = lower(?)",
                        Integer.class,
                        ownerEntityId,
                        nameEn);
        return count != null && count > 0;
    }

    /** The catalogue entries of these codes; a code missing from the answer is not in the catalogue. */
    Map<String, CatalogueEntry> catalogue(Collection<String> codes) {
        Map<String, CatalogueEntry> found = new HashMap<>();
        if (codes.isEmpty()) {
            return found;
        }
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        jdbc.query(
                "select permission_code, scope, limits_schema::text as limits_schema from security.permission"
                        + " where permission_code in (" + placeholders + ")",
                rs -> {
                    found.put(
                            rs.getString("permission_code"),
                            new CatalogueEntry(
                                    rs.getString("permission_code"),
                                    rs.getString("scope"),
                                    json(rs.getString("limits_schema"))));
                },
                codes.toArray());
        return found;
    }

    /** Whether the session is the Federation acting entity-wide (m1security V0010). */
    boolean scopeIsFederation() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select security.scope_is_federation()", Boolean.class));
    }

    /**
     * The pairs in force for an entity: the federation defaults and the entity's own. For a
     * template (entity null), the federation defaults alone.
     */
    List<RoleRules.SodRule> pairsFor(UUID entityId) {
        return jdbc.query(
                """
                select sod_pair_id, permission_a, permission_b, mode, owner_entity_id
                  from security.sod_pair
                 where owner_entity_id is null or owner_entity_id = ?
                """,
                (rs, i) -> new RoleRules.SodRule(
                        rs.getObject("sod_pair_id", UUID.class),
                        rs.getString("permission_a"),
                        rs.getString("permission_b"),
                        rs.getString("mode"),
                        rs.getObject("owner_entity_id", UUID.class)),
                entityId);
    }

    Optional<RoleRules.SodRule> pair(UUID sodPairId) {
        return jdbc
                .query(
                        """
                        select sod_pair_id, permission_a, permission_b, mode, owner_entity_id
                          from security.sod_pair
                         where sod_pair_id = ?
                        """,
                        (rs, i) -> new RoleRules.SodRule(
                                rs.getObject("sod_pair_id", UUID.class),
                                rs.getString("permission_a"),
                                rs.getString("permission_b"),
                                rs.getString("mode"),
                                rs.getObject("owner_entity_id", UUID.class)),
                        sodPairId)
                .stream()
                .findFirst();
    }

    Optional<UserRow> user(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "select user_id, home_entity_id, status from security.app_user where user_id = ?",
                        (rs, i) -> new UserRow(
                                rs.getObject("user_id", UUID.class),
                                rs.getObject("home_entity_id", UUID.class),
                                rs.getString("status")),
                        userId)
                .stream()
                .findFirst();
    }

    /** The owner of a location the caller can see; empty for any other. */
    Optional<UUID> locationOwner(UUID locationId) {
        return jdbc
                .queryForList(
                        "select owner_entity_id from party.location where location_id = ?", UUID.class, locationId)
                .stream()
                .findFirst();
    }

    boolean assignmentExists(Assignment a) {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from security.user_role
                 where user_id = ? and role_id = ? and scope_entity_id = ?
                   and scope_location_id is not distinct from ?
                """,
                Integer.class,
                a.userId(),
                a.roleId(),
                a.scopeEntityId(),
                a.scopeLocationId());
        return count != null && count > 0;
    }

    /** Every assignment at the entity the caller can see, of one user or (null) of everybody. */
    List<Assignment> assignmentsAt(UUID entityId, UUID userId) {
        return jdbc.query(
                """
                select user_id, role_id, scope_entity_id, scope_location_id
                  from security.user_role
                 where scope_entity_id = ?
                   and (cast(? as uuid) is null or user_id = ?)
                """,
                (rs, i) -> new Assignment(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("role_id", UUID.class),
                        rs.getObject("scope_entity_id", UUID.class),
                        rs.getObject("scope_location_id", UUID.class)),
                entityId,
                userId,
                userId);
    }

    /** The union of the permissions of these roles, ACTIVE roles only. */
    Set<String> permissionsOfActiveRoles(Collection<UUID> roleIds) {
        Set<String> found = new LinkedHashSet<>();
        if (roleIds.isEmpty()) {
            return found;
        }
        String placeholders = String.join(",", roleIds.stream().map(r -> "?").toList());
        found.addAll(jdbc.queryForList(
                "select distinct rp.permission_code from security.role_permission rp"
                        + " join security.role r on r.role_id = rp.role_id and r.status = 'ACTIVE'"
                        + " where rp.role_id in (" + placeholders + ")",
                String.class,
                roleIds.toArray()));
        return found;
    }

    /**
     * What each user holds at the entity through the roles they are assigned there, leaving out
     * one role (the one being changed). Only what the caller can see is counted.
     */
    Map<UUID, Set<String>> holdingsAt(UUID entityId, Collection<UUID> users, UUID leavingOutRoleId) {
        Map<UUID, Set<String>> byUser = new HashMap<>();
        for (UUID user : users) {
            List<UUID> roles = new ArrayList<>();
            for (Assignment a : assignmentsAt(entityId, user)) {
                if (!a.roleId().equals(leavingOutRoleId)) {
                    roles.add(a.roleId());
                }
            }
            byUser.put(user, new HashSet<>(permissionsOfActiveRoles(roles)));
        }
        return byUser;
    }

    /** How many users of the entity still hold the role, counting across entities for a template. */
    long assignmentCount(UUID roleId) {
        Long count = jdbc.queryForObject("select security.role_assignment_count(?)", Long.class, roleId);
        return count == null ? 0 : count;
    }

    /**
     * The entity-wide assignments at the entity through which a user who is not DEACTIVATED holds
     * {@code gov.user.manage}: the same fact ActivateEntity asks for (m1security V0003).
     */
    List<Assignment> userManagerHoldings(UUID entityId) {
        return jdbc.query(
                """
                select ur.user_id, ur.role_id, ur.scope_entity_id, ur.scope_location_id
                  from security.user_role ur
                  join security.app_user u on u.user_id = ur.user_id and u.status <> 'DEACTIVATED'
                  join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
                  join security.role_permission rp on rp.role_id = r.role_id and rp.permission_code = ?
                 where ur.scope_entity_id = ?
                   and ur.scope_location_id is null
                """,
                (rs, i) -> new Assignment(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("role_id", UUID.class),
                        rs.getObject("scope_entity_id", UUID.class),
                        rs.getObject("scope_location_id", UUID.class)),
                USER_MANAGE,
                entityId);
    }

    /** The entity's own ACTIVE roles that hold both codes. */
    List<UUID> rolesHoldingBoth(UUID entityId, String a, String b) {
        return jdbc.queryForList(
                """
                select r.role_id
                  from security.role r
                 where r.owner_entity_id = ? and r.status = 'ACTIVE'
                   and exists (select 1 from security.role_permission p
                                where p.role_id = r.role_id and p.permission_code = ?)
                   and exists (select 1 from security.role_permission p
                                where p.role_id = r.role_id and p.permission_code = ?)
                """,
                UUID.class,
                entityId,
                a,
                b);
    }

    /** The users who hold both codes at the entity, through one role or several. */
    List<UUID> usersHoldingBoth(UUID entityId, String a, String b) {
        return jdbc.queryForList(
                """
                select ur.user_id
                  from security.user_role ur
                  join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
                  join security.role_permission rp on rp.role_id = r.role_id
                 where ur.scope_entity_id = ?
                   and rp.permission_code in (?, ?)
                 group by ur.user_id
                having count(distinct rp.permission_code) = 2
                """,
                UUID.class,
                entityId,
                a,
                b);
    }

    String toJson(Map<String, Object> limits) {
        if (limits == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(limits);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Limits cannot be written as JSON", e);
        }
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

    private JsonNode json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("security.permission.limits_schema is not JSON: " + text, e);
        }
    }
}
