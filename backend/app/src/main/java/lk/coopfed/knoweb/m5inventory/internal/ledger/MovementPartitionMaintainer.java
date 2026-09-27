package lk.coopfed.knoweb.m5inventory.internal.ledger;

import lk.coopfed.knoweb.kernel.api.PartitionMaintainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the monthly partitions of {@code inventory.stock_movement} ahead of the calendar (doc 25
 * section 9.1: "partitioned monthly by received_at"). The kernel's partition-maintenance job calls
 * it daily; the work is {@code inventory.ensure_movement_partitions} (m5inventory V0001), as M2's
 * {@code BatchPartitionMaintainer} does for its batches, with the same horizon of three months.
 * The default partition catches a movement whose month has no partition yet.
 */
@Component
class MovementPartitionMaintainer implements PartitionMaintainer {

    static final int MONTHS_AHEAD = 3;

    private final JdbcTemplate jdbc;

    MovementPartitionMaintainer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String table() {
        return "inventory.stock_movement";
    }

    @Override
    public int ensurePartitions() {
        Integer created =
                jdbc.queryForObject("select inventory.ensure_movement_partitions(?)", Integer.class, MONTHS_AHEAD);
        return created == null ? 0 : created;
    }
}
