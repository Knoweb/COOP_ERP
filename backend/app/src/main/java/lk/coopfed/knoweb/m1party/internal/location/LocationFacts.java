package lk.coopfed.knoweb.m1party.internal.location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Two facts the location handlers read from tables that are not the location's own, both in
 * M1's schemas and both read under the caller's row-level security. Read only: the handlers
 * write.
 */
@Component
class LocationFacts {

    private final JdbcTemplate jdbc;

    LocationFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The device assigned to a position, if any (party.device.current_till_position_id; one per
     * position by the unique index). M1-06 assigns devices; until then no position has one.
     */
    Optional<UUID> deviceAt(UUID tillPositionId) {
        List<UUID> devices = jdbc.queryForList(
                "select device_id from party.device where current_till_position_id = ?", UUID.class, tillPositionId);
        return devices.stream().findFirst();
    }

    /**
     * Whether the shop has an operator: a till user (kind TILL or BOTH, not deactivated) with a
     * role assignment scoped to that location (21A section 6, ActivateLocation "at least one
     * operator"; section 7, ListOperators).
     */
    boolean hasOperator(UUID ownerEntityId, UUID locationId) {
        Boolean found = jdbc.queryForObject(
                """
                select exists (
                    select 1
                      from security.user_role ur
                      join security.app_user u on u.user_id = ur.user_id
                     where ur.scope_entity_id = ?
                       and ur.scope_location_id = ?
                       and u.user_kind in ('TILL', 'BOTH')
                       and u.status <> 'DEACTIVATED'
                )
                """,
                Boolean.class,
                ownerEntityId,
                locationId);
        return Boolean.TRUE.equals(found);
    }
}
