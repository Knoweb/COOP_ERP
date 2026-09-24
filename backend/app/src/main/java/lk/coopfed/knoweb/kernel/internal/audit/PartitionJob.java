package lk.coopfed.knoweb.kernel.internal.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PartitionJob {

    private final JdbcTemplate jdbc;

    public PartitionJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
    public void createUpcomingPartitions() {

        jdbc.execute("select kernel.ensure_audit_partitions(3)");

        jdbc.execute("select kernel.ensure_event_outbox_partitions(3)");
    }
}
