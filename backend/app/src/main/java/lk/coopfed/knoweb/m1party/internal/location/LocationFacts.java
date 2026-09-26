package lk.coopfed.knoweb.m1party.internal.location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Facts the location handlers read from tables that are not the location's own, all in M1's
 * schemas and all read under the caller's row-level security. Read only: the handlers write.
 */
@Component
class LocationFacts {

    /** A device at a position, as the location handlers see it. */
    record DeviceAt(UUID deviceId, String status) {

        boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    LocationFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The device assigned to a position, whatever its status, if any
     * (party.device.current_till_position_id; one per position by the unique index). A
     * SUSPENDED device keeps its position until a replacement takes it (M1-06), so the device
     * here may be one that no longer trades.
     */
    Optional<UUID> deviceAt(UUID tillPositionId) {
        return holderAt(tillPositionId).map(DeviceAt::deviceId);
    }

    /** The device at the position with its status: SetPrimaryTill moves counters only to an ACTIVE one. */
    Optional<DeviceAt> holderAt(UUID tillPositionId) {
        List<DeviceAt> devices = jdbc.query(
                "select device_id, status from party.device where current_till_position_id = ?",
                (rs, row) -> new DeviceAt(rs.getObject("device_id", UUID.class), rs.getString("status")),
                tillPositionId);
        return devices.stream().findFirst();
    }

    /**
     * Whether the shop has an operator: an ACTIVE till user (kind TILL or BOTH) with a role
     * assignment scoped to that location (21A section 6, ActivateLocation "at least one
     * operator"; section 7, ListOperators). A PENDING user has no PIN yet and a LOCKED one
     * cannot sign in, so neither can open the till (the review of M1-05).
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
                       and u.status = 'ACTIVE'
                )
                """,
                Boolean.class,
                ownerEntityId,
                locationId);
        return Boolean.TRUE.equals(found);
    }
}
