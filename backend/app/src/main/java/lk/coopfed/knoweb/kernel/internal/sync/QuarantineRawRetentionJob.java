package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The retention of a quarantined event's raw text (wave 2, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (5); CR-32-1 item 2):
 * nightly, the raw event of every row resolved more than {@code sync.quarantine.raw_retention_days}
 * (30 by default) ago is nulled. Only resolved rows: an unresolved one is a fact nobody has looked
 * at yet, and S4 says it is never lost. The row itself stays, with its reason and its resolution,
 * as the record that a fact was refused and why.
 *
 * <p>The work is the database function {@code kernel.sync_quarantine_drop_raw} (kernel V0084), as
 * the change-log purge is {@code kernel.change_log_purge}: the rows of every entity in one
 * statement, and the application role holds no UPDATE on the raw event.
 */
@Component
public class QuarantineRawRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(QuarantineRawRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final SyncSettings settings;
    private final Clock clock;

    QuarantineRawRetentionJob(JdbcTemplate jdbc, SystemScope system, SyncSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.system = system;
        this.settings = settings;
        this.clock = clock;
    }

    @ScheduledJob(
            name = "sync-quarantine-raw-retention",
            cron = "0 35 1 * * *",
            lockTimeout = "PT30M",
            maxRuntime = "PT10M")
    public int dropRaw() {
        return dropRawResolvedBefore(clock.instant().minus(settings.quarantineRawRetention()));
    }

    /** Nulls the raw event of the rows resolved before {@code before}; public for the test. */
    public int dropRawResolvedBefore(Instant before) {
        Long dropped = system.inScope(
                SystemScope.federationView(),
                () -> jdbc.queryForObject(
                        "select kernel.sync_quarantine_drop_raw(?)", Long.class, Timestamp.from(before)));
        long count = dropped == null ? 0 : dropped;
        log.info("Quarantine retention: the raw event of {} rows resolved before {} removed", count, before);
        return (int) Math.min(Integer.MAX_VALUE, count);
    }
}
