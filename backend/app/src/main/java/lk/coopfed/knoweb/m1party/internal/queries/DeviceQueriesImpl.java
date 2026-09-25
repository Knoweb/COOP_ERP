package lk.coopfed.knoweb.m1party.internal.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.DeviceFilter;
import lk.coopfed.knoweb.m1party.query.DeviceQueries;
import lk.coopfed.knoweb.m1party.query.DeviceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ListDevices of 21A section 7, with the position a device holds and whether it is its shop's
 * primary till. Row-level security on party.device decides which rows (V0009); the joins are
 * left joins so a device is listed even when the caller cannot see its position's row.
 */
@Service
@Transactional(readOnly = true)
class DeviceQueriesImpl implements DeviceQueries {

    private static final String SELECT =
            """
            select d.device_id, d.owner_entity_id, d.location_id, d.hardware_serial, d.device_kind, d.status,
                   d.current_till_position_id, p.position_no,
                   (l.primary_till_position_id is not null
                        and l.primary_till_position_id = d.current_till_position_id) as primary_till,
                   d.app_version, d.enrolled_at, d.last_seen_at
              from party.device d
              left join party.till_position p on p.till_position_id = d.current_till_position_id
              left join party.location l on l.location_id = d.location_id
            """;

    private final JdbcTemplate jdbc;

    DeviceQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DeviceView> getDevice(UUID deviceId, ScopeContext scope) {
        if (deviceId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " where d.device_id = ?", DeviceQueriesImpl::map, deviceId).stream()
                .findFirst();
    }

    @Override
    public List<DeviceView> listDevices(DeviceFilter filter, ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT).append(" where true");
        List<Object> args = new ArrayList<>();
        if (filter != null && filter.locationId() != null) {
            sql.append(" and d.location_id = ?");
            args.add(filter.locationId());
        }
        if (filter != null && filter.status() != null && !filter.status().isBlank()) {
            sql.append(" and d.status = ?");
            args.add(filter.status());
        }
        sql.append(" order by d.location_id, d.hardware_serial");
        return jdbc.query(sql.toString(), DeviceQueriesImpl::map, args.toArray());
    }

    private static DeviceView map(ResultSet rs, int row) throws SQLException {
        int positionNo = rs.getInt("position_no");
        Integer position = rs.wasNull() ? null : positionNo;
        return new DeviceView(
                rs.getObject("device_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getString("hardware_serial"),
                rs.getString("device_kind"),
                rs.getString("status"),
                rs.getObject("current_till_position_id", UUID.class),
                position,
                rs.getBoolean("primary_till"),
                rs.getString("app_version"),
                instant(rs.getTimestamp("enrolled_at")),
                instant(rs.getTimestamp("last_seen_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
