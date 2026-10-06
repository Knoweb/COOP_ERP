package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.AppVersionFloor;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The floor and its grace (doc 31 section 6: "when a release raises the floor, devices below it
 * get a configurable grace period (default 14 days) during which sync continues"). After the
 * grace a device's batches are still accepted (CR-30-1 point 4, CR-32-1 item 1: facts are always
 * accepted) and its snapshot and change log are withheld, 426 (SyncController).
 *
 * <p>When does the grace of a version start? At the moment the floor rose above it and stayed
 * there. The register keeps every value it was ever given ({@code kernel.config_value} is
 * append-only), so the answer is read from its history: newest first, walk back while the floor
 * was still above the version; the oldest such value is when it began. A second raise does not
 * restart the grace of a device that was already below the first. The floor is federation-wide
 * ({@code scope_kind FEDERATION}), so the history has no entity or location rows.
 */
@Component
class AppVersionFloors implements AppVersionFloor {

    static final String FLOOR = "sync.app_version_floor";

    /** How far back the history is read: a floor changed more often than this is not a floor. */
    private static final int HISTORY_ROWS = 200;

    private final SyncSettings settings;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SystemScope system;

    AppVersionFloors(SyncSettings settings, JdbcTemplate jdbc, Clock clock, SystemScope system) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.clock = clock;
        this.system = system;
    }

    @Override
    public Standing standing(String appVersion, ScopeContext scope) {
        String floor = settings.appVersionFloor(scope);
        if (AppVersions.atLeast(appVersion, floor)) {
            return new Standing(floor, false, null, false);
        }
        Instant now = clock.instant();
        Instant graceEndsAt = belowSince(appVersion).plus(settings.appVersionFloorGrace(scope));
        return new Standing(floor, true, graceEndsAt, !now.isBefore(graceEndsAt));
    }

    /**
     * Where the device's application stands, by the version it last reported (its latest heartbeat
     * or batch, whichever names the higher version: an updated till reports the new one on the
     * first of either). A device that never reported a version is not held below the floor. Read
     * in the device's own scope, inside a transaction of its own: the call it serves (a snapshot,
     * a change page) carries no version.
     */
    Standing standingOfDevice(ScopeContext device) {
        List<String> reported = system.inScope(
                device,
                () -> jdbc.queryForList(
                        """
                        select app_version from kernel.device_heartbeat where device_id = ?
                        union all
                        select app_version from kernel.device_sync_cursor where device_id = ?
                        """,
                        String.class,
                        device.deviceId(),
                        device.deviceId()));
        String latest = null;
        for (String version : reported) {
            if (version != null && (latest == null || AppVersions.atLeast(version, latest))) {
                latest = version;
            }
        }
        if (latest == null) {
            return new Standing(settings.appVersionFloor(device), false, null, false);
        }
        return standing(latest, device);
    }

    /**
     * When the floor last rose above {@code appVersion} and stayed there. A floor that is only
     * the register's default (never set) has no history: the version has been below it forever.
     */
    private Instant belowSince(String appVersion) {
        List<FloorValue> history = jdbc.query(
                """
                select value #>> '{}' as floor, effective_from
                  from kernel.config_value
                 where key = ? and scope_entity_id is null and scope_location_id is null
                   and effective_from <= now()
                 order by effective_from desc
                 limit ?
                """,
                (rs, n) -> new FloorValue(rs.getString("floor"), rs.getTimestamp("effective_from")),
                FLOOR,
                HISTORY_ROWS);
        Instant since = Instant.EPOCH;
        for (FloorValue value : history) {
            if (AppVersions.atLeast(appVersion, value.floor())) {
                break;
            }
            since = value.effectiveFrom().toInstant();
        }
        return since;
    }

    private record FloorValue(String floor, Timestamp effectiveFrom) {}
}
