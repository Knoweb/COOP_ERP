package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotManifest.Row;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotManifest.Table;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotManifest.Tombstone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a shop's snapshot for its till (doc 32 section 5.2; 19A section 8, "snapshot builder"):
 * the delta since the version the till holds, or the full snapshot when it holds none, is ahead
 * of central, or is older than the change-log retention.
 *
 * <p>How a delta is made, step by step:
 *
 * <ol>
 *   <li>Read the shop's current version V.
 *   <li>Read the change log after the till's version up to V and keep, per row, the last thing
 *       that happened to it (a row upserted and then deleted is a tombstone).
 *   <li>Ask the contributor of each table for the current rows of the upserted ids. An id it does
 *       not return has left the shop's snapshot and becomes a tombstone as well.
 *   <li>Sign a manifest of the tables (row counts and hashes) with the till signing key.
 * </ol>
 *
 * <p>Why the answer is exactly version V (doc 32 S5, "applied whole or not at all"): everything is
 * read in one read-only transaction at REPEATABLE READ, so every query sees the database as it
 * was at the first one. A producer writes its rows and its change-log entry in one transaction,
 * so the builder sees both or neither; a publication that commits while the builder runs is in
 * the next version, not half in this one.
 *
 * <p>The full snapshot is served in the answer itself (every row of every contributor's table),
 * not yet as the pre-built file in object storage doc 32 describes; see PROGRESS, Deviations.
 */
@Component
public class SnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);

    /** What the builder answers: the tables and the signed manifest over them. */
    record Snapshot(
            UUID locationId,
            long since,
            long version,
            boolean full,
            boolean urgent,
            Map<String, Table> tables,
            String manifest,
            String signature,
            String keyId) {}

    /** The last change-log entry of one row in the range the till asks for. */
    private record LoggedChange(String table, UUID rowId, String op, LocalDate applyFrom) {}

    private final Map<String, SnapshotContributor> contributors = new TreeMap<>();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TillSigner signer;

    SnapshotBuilder(List<SnapshotContributor> contributorBeans, JdbcTemplate jdbc, Clock clock, TillSigner signer) {
        for (SnapshotContributor contributor : contributorBeans) {
            for (String table : contributor.tables()) {
                SnapshotContributor earlier = contributors.put(table, contributor);
                if (earlier != null) {
                    throw new IllegalStateException("Two snapshot contributors serve the table " + table + ": "
                            + earlier.getClass().getName() + " and "
                            + contributor.getClass().getName());
                }
            }
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.signer = signer;
    }

    /** The tables some contributor serves, in name order. */
    Collection<String> tables() {
        return contributors.keySet();
    }

    /**
     * @param device    the device's scope: its entity and its shop (row-level security applies)
     * @param since     the version the device holds; 0 when it holds none
     * @param retention the change-log retention (doc 32 DR-4)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot build(ScopeContext device, long since, Duration retention) {
        UUID location = device.locationId();
        Shop shop = new Shop(device.entityId(), location);
        long current = currentVersion(location);

        boolean full = since == 0 || since > current || olderThanRetention(location, since, current, retention);
        Map<String, Table> tables = full ? fullTables(shop) : deltaTables(shop, since, current);
        boolean urgent = !full && urgentBetween(location, since, current);

        String manifest = SnapshotManifest.manifest(location, since, current, full, tables);
        return new Snapshot(
                location, since, current, full, urgent, tables, manifest, signer.sign(manifest), signer.keyId());
    }

    // ---- the delta ----------------------------------------------------------------------------

    private Map<String, Table> deltaTables(Shop shop, long since, long current) {
        // Per table, the rows to upsert and the rows to remove, each with its apply_from.
        Map<String, Map<UUID, LocalDate>> upserts = new TreeMap<>();
        Map<String, Map<UUID, LocalDate>> deletes = new TreeMap<>();
        for (LoggedChange change : lastChangePerRow(shop.locationId(), since, current)) {
            Map<String, Map<UUID, LocalDate>> target = "DELETE".equals(change.op()) ? deletes : upserts;
            target.computeIfAbsent(change.table(), t -> new LinkedHashMap<>()).put(change.rowId(), change.applyFrom());
        }

        Map<String, Table> tables = new TreeMap<>();
        for (Map.Entry<String, Map<UUID, LocalDate>> entry : upserts.entrySet()) {
            String table = entry.getKey();
            SnapshotContributor contributor = contributors.get(table);
            if (contributor == null) {
                // The producer's module has no contributor yet: nothing can say what the row is.
                // Logged, and left out of the delta rather than blocking the till on it.
                log.error(
                        "No snapshot contributor serves the table {}: {} changed rows left out",
                        table,
                        entry.getValue().size());
                continue;
            }
            Map<UUID, LocalDate> wanted = entry.getValue();
            Map<UUID, Map<String, Object>> found = contributor.rows(table, shop, wanted.keySet());
            List<Row> rows = new ArrayList<>();
            List<Tombstone> gone = new ArrayList<>();
            for (Map.Entry<UUID, LocalDate> row : wanted.entrySet()) {
                Map<String, Object> data = found.get(row.getKey());
                if (data == null) {
                    gone.add(new Tombstone(row.getKey(), row.getValue()));
                } else {
                    rows.add(new Row(row.getKey(), row.getValue(), data));
                }
            }
            tables.put(table, new Table(rows, gone));
        }
        for (Map.Entry<String, Map<UUID, LocalDate>> entry : deletes.entrySet()) {
            List<Tombstone> gone = new ArrayList<>();
            entry.getValue().forEach((rowId, applyFrom) -> gone.add(new Tombstone(rowId, applyFrom)));
            Table before = tables.get(entry.getKey());
            if (before == null) {
                tables.put(entry.getKey(), new Table(List.of(), gone));
            } else {
                List<Tombstone> all = new ArrayList<>(before.tombstones());
                all.addAll(gone);
                tables.put(entry.getKey(), new Table(before.upserts(), all));
            }
        }
        return tables;
    }

    private List<LoggedChange> lastChangePerRow(UUID location, long since, long current) {
        return jdbc.query(
                """
                select distinct on (table_name, row_id) table_name, row_id, op, apply_from
                  from kernel.change_log
                 where location_id = ? and version > ? and version <= ?
                 order by table_name, row_id, version desc
                """,
                (rs, n) -> new LoggedChange(
                        rs.getString("table_name"),
                        rs.getObject("row_id", UUID.class),
                        rs.getString("op"),
                        toLocalDate(rs.getDate("apply_from"))),
                location,
                since,
                current);
    }

    // ---- the full snapshot --------------------------------------------------------------------

    private Map<String, Table> fullTables(Shop shop) {
        Map<String, Map<UUID, LocalDate>> applyFrom = latestApplyFrom(shop.locationId());
        Map<String, Table> tables = new TreeMap<>();
        for (Map.Entry<String, SnapshotContributor> entry : contributors.entrySet()) {
            String table = entry.getKey();
            Map<UUID, LocalDate> dates = applyFrom.getOrDefault(table, Map.of());
            List<Row> rows = new ArrayList<>();
            entry.getValue()
                    .allRows(table, shop)
                    .forEach((rowId, data) -> rows.add(new Row(rowId, dates.get(rowId), data)));
            tables.put(table, new Table(rows, List.of()));
        }
        return tables;
    }

    /**
     * The apply_from of the latest change-log entry of each row, where it has one: a row
     * published for a later business date is held on the till in the full snapshot as well.
     */
    private Map<String, Map<UUID, LocalDate>> latestApplyFrom(UUID location) {
        Map<String, Map<UUID, LocalDate>> dates = new HashMap<>();
        jdbc.query(
                """
                select table_name, row_id, apply_from
                  from (select distinct on (table_name, row_id) table_name, row_id, apply_from
                          from kernel.change_log
                         where location_id = ?
                         order by table_name, row_id, version desc) latest
                 where apply_from is not null
                """,
                rs -> {
                    dates.computeIfAbsent(rs.getString("table_name"), t -> new HashMap<>())
                            .put(rs.getObject("row_id", UUID.class), toLocalDate(rs.getDate("apply_from")));
                },
                location);
        return dates;
    }

    // ---- versions -----------------------------------------------------------------------------

    private long currentVersion(UUID location) {
        List<Long> versions = jdbc.queryForList(
                "select current_version from kernel.location_snapshot_version where location_id = ?",
                Long.class,
                location);
        return versions.isEmpty() ? 0 : versions.getFirst();
    }

    /**
     * As the change-log reader decides it: the log no longer holds everything after the device's
     * version, because the nightly purge cut it there (kernel V0082), or the oldest entry the
     * device needs is past the retention.
     */
    private boolean olderThanRetention(UUID location, long since, long current, Duration retention) {
        if (since >= current) {
            return false;
        }
        if (since < purgedThrough(jdbc, location)) {
            return true;
        }
        Timestamp oldestNeeded = jdbc.queryForObject(
                "select min(recorded_at) from kernel.change_log where location_id = ? and version > ?",
                Timestamp.class,
                location,
                since);
        return oldestNeeded == null
                || oldestNeeded.toInstant().isBefore(clock.instant().minus(retention));
    }

    /** How far the nightly purge cut the location's change log (ChangeLogPurgeJob); 0 when never. */
    static long purgedThrough(JdbcTemplate jdbc, UUID location) {
        List<Long> purged = jdbc.queryForList(
                "select purged_through_version from kernel.location_snapshot_version where location_id = ?",
                Long.class,
                location);
        return purged.isEmpty() ? 0 : purged.getFirst();
    }

    private boolean urgentBetween(UUID location, long since, long current) {
        Boolean urgent = jdbc.queryForObject(
                "select coalesce(bool_or(urgent), false) from kernel.change_log"
                        + " where location_id = ? and version > ? and version <= ?",
                Boolean.class,
                location,
                since,
                current);
        return Boolean.TRUE.equals(urgent);
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
