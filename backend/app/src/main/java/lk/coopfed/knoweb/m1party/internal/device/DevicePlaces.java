package lk.coopfed.knoweb.m1party.internal.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The locations and till positions the device handlers read: where a device is enrolled and
 * which lane it takes. Both tables are M1's own (party.location, party.till_position), read
 * under the caller's row-level security, so a place outside the caller's scope is simply not
 * found. Read only: the location handlers of M1-05 write these tables.
 *
 * <p>Plain SQL rather than the location package's entities on purpose: the device handlers
 * need four facts of a position and none of its behaviour.
 */
@Component
class DevicePlaces {

    /** A location as a device sees it. */
    record Place(UUID locationId, UUID ownerEntityId, String locationType, UUID primaryTillPositionId) {}

    /** A till position as a device sees it. */
    record Lane(UUID tillPositionId, UUID locationId, UUID ownerEntityId, int positionNo, String status) {

        boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    DevicePlaces(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Place> location(UUID locationId) {
        List<Place> found = jdbc.query(
                "select location_id, owner_entity_id, location_type, primary_till_position_id"
                        + " from party.location where location_id = ?",
                (rs, row) -> new Place(
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("location_type"),
                        rs.getObject("primary_till_position_id", UUID.class)),
                locationId);
        return found.stream().findFirst();
    }

    Optional<Lane> position(UUID tillPositionId) {
        return position(tillPositionId, false);
    }

    /**
     * The position, locked until the caller's transaction ends ({@code FOR UPDATE}): two
     * assignments to one position run one after the other, so the second sees the holder the
     * first left there (the review of M1-06). The lock is on M1's own row; RetireTillPosition
     * takes the same lock, so a position is not retired under a device being assigned to it.
     */
    Optional<Lane> positionForUpdate(UUID tillPositionId) {
        return position(tillPositionId, true);
    }

    private Optional<Lane> position(UUID tillPositionId, boolean forUpdate) {
        List<Lane> found = jdbc.query(
                "select till_position_id, location_id, owner_entity_id, position_no, status"
                        + " from party.till_position where till_position_id = ?"
                        + (forUpdate ? " for update" : ""),
                (rs, row) -> new Lane(
                        rs.getObject("till_position_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getInt("position_no"),
                        rs.getString("status")),
                tillPositionId);
        return found.stream().findFirst();
    }
}
