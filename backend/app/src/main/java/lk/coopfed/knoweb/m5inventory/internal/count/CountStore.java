package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The count handlers' reads, in their transaction and scope (row-level security applies). */
@Component
class CountStore {

    record Task(
            UUID taskId,
            UUID ownerEntityId,
            UUID locationId,
            String scopeKind,
            List<UUID> skuIds,
            String status,
            UUID submittedBy,
            BigDecimal reviewValue,
            Integer reviewBand,
            Instant startedAt) {}

    /** A lot of the location as the ledger holds it now. */
    record Lot(UUID batchId, UUID skuId, String condition, BigDecimal qtyOnHand) {}

    record Line(
            UUID lineId,
            UUID batchId,
            String condition,
            BigDecimal varianceQty,
            boolean withinTolerance,
            BigDecimal unitCost) {}

    private final JdbcTemplate jdbc;

    CountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The task, locked for the rest of the transaction; empty when the scope does not see it. */
    Optional<Task> lock(UUID taskId) {
        return jdbc
                .query(
                        """
                        select task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, status,
                               submitted_by, review_value, review_band, started_at
                          from inventory.count_task
                         where task_id = ?
                           for update
                        """,
                        (rs, n) -> {
                            Array skus = rs.getArray("scope_sku_ids");
                            OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
                            return new Task(
                                    rs.getObject("task_id", UUID.class),
                                    rs.getObject("owner_entity_id", UUID.class),
                                    rs.getObject("location_id", UUID.class),
                                    rs.getString("scope_kind"),
                                    skus == null ? List.of() : Arrays.asList((UUID[]) skus.getArray()),
                                    rs.getString("status"),
                                    rs.getObject("submitted_by", UUID.class),
                                    rs.getBigDecimal("review_value"),
                                    (Integer) rs.getObject("review_band"),
                                    startedAt == null ? null : startedAt.toInstant());
                        },
                        taskId)
                .stream()
                .findFirst();
    }

    boolean openAt(UUID locationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.count_task where location_id = ? and status <> 'CLOSED')",
                Boolean.class,
                locationId));
    }

    /** The lots of the location in the task's scope, with stock or owing it (a negative lot is counted too). */
    List<Lot> lotsInScope(Task task) {
        return jdbc.query(
                """
                select batch_id, sku_id, condition, qty_on_hand
                  from inventory.stock_lot
                 where location_id = ? and qty_on_hand <> 0
                   and (? = 'FULL' or sku_id = any (?))
                 order by sku_id, condition, expiry_date nulls last, batch_id
                """,
                (rs, n) -> new Lot(
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("qty_on_hand")),
                task.locationId(),
                task.scopeKind(),
                task.skuIds().toArray(new UUID[0]));
    }

    /**
     * What the lot held at a moment (wave 2, M5-13): what it holds now less every movement of it
     * that occurred after the moment (by {@code occurred_at}, so a till's sale uploaded late falls
     * on the right side). Zero when there is no lot.
     *
     * <p>One statement, so both reads see one snapshot (wave 3, M1M2M3M5-21): read in two, a sale
     * committing between them was in the sum but not in the quantity, and the book came out short
     * (a phantom surplus that could auto-post).
     */
    BigDecimal onHandAt(UUID locationId, UUID batchId, String condition, Instant moment) {
        BigDecimal held = jdbc.queryForObject(
                """
                select coalesce((select qty_on_hand from inventory.stock_lot
                                  where location_id = ? and batch_id = ? and condition = ?), 0)
                     - coalesce((select sum(qty_delta) from inventory.stock_movement
                                  where location_id = ? and batch_id = ? and condition = ? and occurred_at > ?), 0)
                """,
                BigDecimal.class,
                locationId,
                batchId,
                condition,
                locationId,
                batchId,
                condition,
                Timestamp.from(moment));
        return held == null ? BigDecimal.ZERO : held;
    }

    /** What the lot holds now; zero when there is none. */
    BigDecimal onHand(UUID locationId, UUID batchId, String condition) {
        List<BigDecimal> found = jdbc.queryForList(
                "select qty_on_hand from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                BigDecimal.class,
                locationId,
                batchId,
                condition);
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    List<Lot> expectation(UUID taskId) {
        return jdbc.query(
                """
                select batch_id, sku_id, condition, expected_qty
                  from inventory.count_expectation
                 where task_id = ?
                """,
                (rs, n) -> new Lot(
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("expected_qty")),
                taskId);
    }

    List<Line> lines(UUID taskId) {
        return jdbc.query(
                """
                select line_id, batch_id, condition, variance_qty, within_tolerance, unit_cost
                  from inventory.count_line
                 where task_id = ?
                 order by line_no
                """,
                (rs, n) -> new Line(
                        rs.getObject("line_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("variance_qty"),
                        rs.getBoolean("within_tolerance"),
                        rs.getBigDecimal("unit_cost")),
                taskId);
    }
}
