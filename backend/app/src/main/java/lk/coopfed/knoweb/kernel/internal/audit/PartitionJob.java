package lk.coopfed.knoweb.kernel.internal.audit;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.PartitionMaintainer;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the monthly partitions of the audit log and the event outbox ahead of the calendar
 * (19A section 12, "partition-maintenance (daily)"), then every partitioned table a module
 * registered through {@link PartitionMaintainer} (M2's {@code catalogue.batch}).
 */
@Component
public class PartitionJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionJob.class);

    private final JdbcTemplate jdbc;
    private final List<PartitionMaintainer> maintainers;

    public PartitionJob(JdbcTemplate jdbc, List<PartitionMaintainer> maintainers) {
        this.jdbc = jdbc;
        this.maintainers = List.copyOf(maintainers);
    }

    /** Returns the number of partitions the modules' maintainers created, for the run history. */
    @ScheduledJob(name = "partition-maintenance", cron = "0 10 0 * * *", lockTimeout = "PT30M", maxRuntime = "PT10M")
    public int createUpcomingPartitions() {

        jdbc.execute("select kernel.ensure_audit_partitions(3)");

        jdbc.execute("select kernel.ensure_event_outbox_partitions(3)");

        int created = 0;

        for (PartitionMaintainer maintainer : maintainers) {
            int count = maintainer.ensurePartitions();

            if (count > 0) {
                log.info("partition-maintenance created {} partition(s) of {}", count, maintainer.table());
            }

            created += count;
        }

        return created;
    }
}
