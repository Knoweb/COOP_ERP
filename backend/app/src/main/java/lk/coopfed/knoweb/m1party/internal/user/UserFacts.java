package lk.coopfed.knoweb.m1party.internal.user;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The facts the user handlers' guards read, each one query, in the handler's transaction and
 * under the caller's row-level security. Reads only; the handlers write. Deliberately not
 * {@code @Transactional}: a nested transactional call would put its own scope on the connection.
 */
@Component
class UserFacts {

    /** The permission whose last holder an entity must keep (21A section 6, Grant.lastAdminGuard). */
    static final String USER_MANAGE = "gov.user.manage";

    /** An entity-wide assignment of an active role that carries gov.user.manage, as ActivateEntity counts them. */
    private static final String USER_MANAGERS =
            """
            select count(distinct u.user_id)
              from security.app_user u
              join security.user_role ur on ur.user_id = u.user_id
              join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
              join security.role_permission rp on rp.role_id = r.role_id
             where u.home_entity_id = ?
               and u.status <> 'DEACTIVATED'
               and ur.scope_entity_id = ?
               and ur.scope_location_id is null
               and rp.permission_code = ?
               and u.user_id <> ?
            """;

    private static final String HOLDS_USER_MANAGE =
            """
            select exists (
                select 1
                  from security.user_role ur
                  join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
                  join security.role_permission rp on rp.role_id = r.role_id
                 where ur.user_id = ?
                   and ur.scope_entity_id = ?
                   and ur.scope_location_id is null
                   and rp.permission_code = ?)
            """;

    private final JdbcTemplate jdbc;

    UserFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Across the federation, whoever's user it is (m1security V0012). */
    boolean usernameTaken(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select security.username_taken(?)", Boolean.class, username));
    }

    boolean holdsUserManage(UUID userId, UUID entityId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(HOLDS_USER_MANAGE, Boolean.class, userId, entityId, USER_MANAGE));
    }

    /** Whether another user of the entity, not deactivated, holds gov.user.manage entity-wide. */
    boolean anotherUserManagerExists(UUID userId, UUID entityId) {
        Integer others = jdbc.queryForObject(USER_MANAGERS, Integer.class, entityId, entityId, USER_MANAGE, userId);
        return others != null && others > 0;
    }

    /** Whether the user is the entity's responsible officer (doc 21 DR-1). */
    boolean isResponsibleOfficer(UUID userId, UUID entityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from party.entity where entity_id = ? and responsible_officer_user_id = ?)",
                Boolean.class,
                entityId,
                userId));
    }
}
