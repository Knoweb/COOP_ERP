package lk.coopfed.knoweb.m2catalogue.internal.batch;

import lk.coopfed.knoweb.kernel.api.PartitionMaintainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the monthly partitions of {@code catalogue.batch} ahead of the calendar (doc 22 section
 * 9.1). The kernel's partition-maintenance job calls it daily, after the kernel's own tables;
 * the work is {@code catalogue.ensure_batch_partitions} (m2catalogue V0003), which creates the
 * missing partitions of this month and the months after it, with row-level security forced
 * and the parent's policies copied, and returns how many it created.
 *
 * <p>The horizon is the kernel's for its audit and outbox partitions ({@code PartitionJob}):
 * three months ahead, so a job that fails for a while has time to be noticed. The default
 * partition of batch catches a row that arrives before its month's partition exists.
 */
@Component
class BatchPartitionMaintainer implements PartitionMaintainer {

    static final int MONTHS_AHEAD = 3;

    private final JdbcTemplate jdbc;

    BatchPartitionMaintainer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String table() {
        return "catalogue.batch";
    }

    @Override
    public int ensurePartitions() {
        Integer created =
                jdbc.queryForObject("select catalogue.ensure_batch_partitions(?)", Integer.class, MONTHS_AHEAD);
        return created == null ? 0 : created;
    }
}
