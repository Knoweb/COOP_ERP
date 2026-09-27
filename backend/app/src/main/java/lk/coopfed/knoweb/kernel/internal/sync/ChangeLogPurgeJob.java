package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The change-log retention of doc 32 DR-4 (30 days, {@code sync.change_log.retention}): a till
 * whose version is older takes a full snapshot, so the entries older than that serve no delta and
 * are removed nightly. The work is the database function {@code kernel.change_log_purge} (kernel
 * V0082): it cuts each shop's log at a version boundary, keeps the latest entry of a row that is
 * still to take effect (its apply_from, which the full snapshot reads from the log), and records
 * how far the log was cut, which the snapshot builder and the change-log reader compare a till's
 * version with. The application role has no DELETE on the log; the function is the only way.
 *
 * <p>A change log is not a ledger of business facts: every row it names is still in its owning
 * module's table, and what a till misses by a purge it receives whole in the full snapshot.
 */
@Component
public class ChangeLogPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(ChangeLogPurgeJob.class);

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final SyncSettings settings;
    private final Clock clock;

    ChangeLogPurgeJob(JdbcTemplate jdbc, SystemScope system, SyncSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.system = system;
        this.settings = settings;
        this.clock = clock;
    }

    @ScheduledJob(name = "sync-change-log-purge", cron = "0 20 1 * * *", lockTimeout = "PT30M", maxRuntime = "PT10M")
    public int purge() {
        return purgeOlderThan(settings.changeLogRetentionFederationWide());
    }

    /**
     * Removes what is older than {@code retention}; public for the test, which cannot wait a
     * month. The function itself refuses to cut the last 24 hours.
     */
    public int purgeOlderThan(Duration retention) {
        Instant before = clock.instant().minus(retention);
        // A day earlier than today in UTC: a row dated for today in Colombo (UTC+5:30) is kept
        // until tomorrow's run rather than risk dropping its date before the shop has opened.
        LocalDate keepApplyFrom =
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
        Long removed = system.inScope(
                SystemScope.federationView(),
                () -> jdbc.queryForObject(
                        "select kernel.change_log_purge(?, ?)",
                        Long.class,
                        Timestamp.from(before),
                        Date.valueOf(keepApplyFrom)));
        long count = removed == null ? 0 : removed;
        log.info("Change-log purge: {} entries recorded before {} removed", count, before);
        return (int) Math.min(Integer.MAX_VALUE, count);
    }
}
