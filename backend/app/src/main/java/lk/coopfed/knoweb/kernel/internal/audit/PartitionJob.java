package lk.coopfed.knoweb.kernel.internal.audit;

import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the monthly partitions of the audit log and the event outbox ahead of the calendar
 * (19A section 12, "partition-maintenance (daily)").
 */
@Component
public class PartitionJob {

    private final JdbcTemplate jdbc;

    public PartitionJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ScheduledJob(name = "partition-maintenance", cron = "0 10 0 * * *", lockTimeout = "PT30M", maxRuntime = "PT10M")
    public void createUpcomingPartitions() {

        jdbc.execute("select kernel.ensure_audit_partitions(3)");

        jdbc.execute("select kernel.ensure_event_outbox_partitions(3)");
    }
}
