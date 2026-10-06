package lk.coopfed.knoweb.m7customers.internal.ledger;

import lk.coopfed.knoweb.kernel.api.PartitionMaintainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the monthly partitions of {@code customers.account_posting} ahead of the calendar (27A
 * section 3: "PARTITION BY RANGE (received_at)"), as M5's MovementPartitionMaintainer does for its
 * movements, with the same horizon. The work is {@code customers.ensure_posting_partitions}
 * (m7customers V0001); the kernel's partition-maintenance job calls it daily.
 */
@Component
class PostingPartitionMaintainer implements PartitionMaintainer {

    static final int MONTHS_AHEAD = 3;

    private final JdbcTemplate jdbc;

    PostingPartitionMaintainer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String table() {
        return "customers.account_posting";
    }

    @Override
    public int ensurePartitions() {
        Integer created =
                jdbc.queryForObject("select customers.ensure_posting_partitions(?)", Integer.class, MONTHS_AHEAD);
        return created == null ? 0 : created;
    }
}
