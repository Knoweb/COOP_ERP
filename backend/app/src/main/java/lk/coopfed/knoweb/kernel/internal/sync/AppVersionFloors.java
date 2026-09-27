package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.AppVersionFloor;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The floor and its grace (doc 31 section 6: "when a release raises the floor, devices below it
 * get a configurable grace period (default 14 days) during which sync continues"; doc 32 section
 * 3.3 step 1: "below floor after grace: 426").
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

    AppVersionFloors(SyncSettings settings, JdbcTemplate jdbc, Clock clock) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.clock = clock;
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
