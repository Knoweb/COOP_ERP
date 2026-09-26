package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a location's change log for its device (doc 32 section 5.2): the entries after the
 * version the device holds, a page at a time, a page ending on a version boundary so that a
 * device never holds half a publication (S5). The producers write through
 * {@link lk.coopfed.knoweb.kernel.api.ChangeLog}; this only reads, in the device's scope, so
 * row-level security shows it its own shop's log.
 */
@Component
public class ChangeLogReader {

    record Entry(
            long version,
            String table,
            UUID rowId,
            String op,
            LocalDate applyFrom,
            boolean urgent,
            Instant recordedAt) {}

    record Page(
            UUID locationId,
            long since,
            long currentVersion,
            boolean fullSnapshotRequired,
            long nextSince,
            boolean hasMore,
            List<Entry> entries) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ChangeLogReader(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param retention the change-log retention (doc 32 DR-4): a device whose version is older
     *                  takes a full snapshot, as does a device with no version (since 0) and one
     *                  ahead of central (central restored from backup)
     */
    @Transactional(readOnly = true)
    public Page read(ScopeContext device, long since, int limit, Duration retention) {
        UUID location = device.locationId();
        List<Long> versions = jdbc.queryForList(
                "select current_version from kernel.location_snapshot_version where location_id = ?",
                Long.class,
                location);
        long current = versions.isEmpty() ? 0 : versions.getFirst();

        // A device with no version takes a full snapshot, unless the shop has none either: a
        // till enrolled before anything was published to its shop has nothing to download, and
        // "full snapshot required" would send it to an endpoint with nothing to serve.
        boolean full = (since == 0 && current > 0) || since > current;
        if (!full && since < current) {
            Timestamp oldestNeeded = jdbc.queryForObject(
                    "select min(recorded_at) from kernel.change_log where location_id = ? and version > ?",
                    Timestamp.class,
                    location,
                    since);
            full = oldestNeeded == null
                    || oldestNeeded.toInstant().isBefore(clock.instant().minus(retention));
        }

        List<Entry> rows = entries(location, since, Long.MAX_VALUE, limit + 1);
        List<Entry> page;
        if (rows.size() <= limit) {
            page = rows;
        } else {
            long lastVersion = rows.get(limit - 1).version();
            if (rows.getFirst().version() == lastVersion) {
                // One publication larger than a page: it goes whole, or the device would hold half.
                page = entries(location, lastVersion - 1, lastVersion, Integer.MAX_VALUE);
            } else {
                page = new ArrayList<>();
                for (Entry entry : rows.subList(0, limit)) {
                    if (entry.version() < lastVersion) {
                        page.add(entry);
                    }
                }
            }
        }
        long next = page.isEmpty() ? Math.min(since, current) : page.getLast().version();
        return new Page(location, since, current, full, next, next < current, page);
    }

    private List<Entry> entries(UUID location, long after, long upTo, int limit) {
        return jdbc.query(
                """
                select version, table_name, row_id, op, apply_from, urgent, recorded_at
                  from kernel.change_log
                 where location_id = ? and version > ? and version <= ?
                 order by version, table_name, row_id
                 limit ?
                """,
                (rs, n) -> {
                    Date applyFrom = rs.getDate("apply_from");
                    return new Entry(
                            rs.getLong("version"),
                            rs.getString("table_name"),
                            rs.getObject("row_id", UUID.class),
                            rs.getString("op"),
                            applyFrom == null ? null : applyFrom.toLocalDate(),
                            rs.getBoolean("urgent"),
                            rs.getTimestamp("recorded_at").toInstant());
                },
                location,
                after,
                upTo,
                limit);
    }
}
