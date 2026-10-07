package lk.coopfed.knoweb.m1party.internal.user;

import java.util.HashSet;
import java.util.Set;
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

    /**
     * An entity-wide assignment of an active role that carries gov.user.manage, held by an
     * ACTIVE user whose kind signs in to the back office (BACK_OFFICE, BOTH). Only ACTIVE: the
     * permission resolver grants nothing to a PENDING or LOCKED user, so such a holder cannot act
     * as a manager and must not count as one; and a TILL-only user has no login to manage users
     * with (wave 2, M1A-03; CR-21A-7).
     */
    private static final String USER_MANAGERS =
            """
            select count(distinct u.user_id)
              from security.app_user u
              join security.user_role ur on ur.user_id = u.user_id
              join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
              join security.role_permission rp on rp.role_id = r.role_id
             where u.home_entity_id = ?
               and u.status = 'ACTIVE'
               and u.user_kind in ('BACK_OFFICE', 'BOTH')
               and ur.scope_entity_id = ?
               and ur.scope_location_id is null
               and rp.permission_code = ?
               and u.user_id <> ?
            """;

    /**
     * The permissions the catalogue marks {@code requires_mfa} (user and role management, the
     * credit limit, the approvals: the sensitive codes) that the user holds at the entity, through
     * any ACTIVE role assigned there, entity-wide or at a location, whatever the user's status: a
     * PENDING or LOCKED user regains them with the credential the caller would set.
     */
    private static final String SENSITIVE_HELD =
            """
            select distinct rp.permission_code
              from security.user_role ur
              join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
              join security.role_permission rp on rp.role_id = r.role_id
              join security.permission p on p.permission_code = rp.permission_code and p.requires_mfa
             where ur.user_id = ?
               and ur.scope_entity_id = ?
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

    /** The {@code requires_mfa} permissions the user holds at the entity ({@link #SENSITIVE_HELD}). */
    Set<String> sensitivePermissionsHeld(UUID userId, UUID entityId) {
        return new HashSet<>(jdbc.queryForList(SENSITIVE_HELD, String.class, userId, entityId));
    }

    /** Whether another ACTIVE back-office user of the entity holds gov.user.manage entity-wide. */
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

    /** Whether the distributor manages the given MPCS entity. */
    boolean isManagingDistributor(UUID distributorId, UUID mpcsId) {
        if (distributorId == null || mpcsId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from party.entity where entity_id = ? and managing_distributor_id = ?)",
                Boolean.class,
                mpcsId,
                distributorId));
    }
}
