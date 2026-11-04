package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Storage queries and mutations for mobile stock-take sessions and individual scan rows (PGL-M2-09).
 */
@Component
class StockTakeStore {

    record ScanRow(
            UUID scanId,
            UUID taskId,
            UUID ownerEntityId,
            UUID locationId,
            String scannedCode,
            UUID skuId,
            UUID batchId,
            String condition,
            BigDecimal countedQty,
            Instant scannedAt,
            UUID scannedBy,
            String deviceId,
            String notes) {}

    record AggregatedLotCount(UUID skuId, UUID batchId, String condition, BigDecimal totalQty, Instant lastScannedAt) {}

    record LotCandidate(UUID batchId, String condition, BigDecimal qtyOnHand, LocalDate expiryDate) {}

    private final JdbcTemplate jdbc;

    StockTakeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CountStore.Task> activeTask(UUID locationId) {
        return jdbc
                .query(
                        """
                        select task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, status,
                               submitted_by, review_value, review_band, started_at
                          from inventory.count_task
                         where location_id = ? and status <> 'CLOSED'
                         order by scheduled_at desc
                         limit 1
                        """,
                        (rs, n) -> {
                            Array skus = rs.getArray("scope_sku_ids");
                            OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
                            return new CountStore.Task(
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
                        locationId)
                .stream()
                .findFirst();
    }

    Optional<CountStore.Task> findTask(UUID taskId) {
        return jdbc
                .query(
                        """
                        select task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, status,
                               submitted_by, review_value, review_band, started_at
                          from inventory.count_task
                         where task_id = ?
                        """,
                        (rs, n) -> {
                            Array skus = rs.getArray("scope_sku_ids");
                            OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
                            return new CountStore.Task(
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

    Optional<ScanRow> findScan(UUID scanId) {
        return jdbc
                .query(
                        """
                        select scan_id, task_id, owner_entity_id, location_id, scanned_code, sku_id, batch_id,
                               condition, counted_qty, scanned_at, scanned_by, device_id, notes
                          from inventory.stock_take_scan
                         where scan_id = ?
                        """,
                        (rs, n) -> new ScanRow(
                                rs.getObject("scan_id", UUID.class),
                                rs.getObject("task_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("scanned_code"),
                                rs.getObject("sku_id", UUID.class),
                                rs.getObject("batch_id", UUID.class),
                                rs.getString("condition"),
                                rs.getBigDecimal("counted_qty"),
                                rs.getObject("scanned_at", OffsetDateTime.class).toInstant(),
                                rs.getObject("scanned_by", UUID.class),
                                rs.getString("device_id"),
                                rs.getString("notes")),
                        scanId)
                .stream()
                .findFirst();
    }

    List<ScanRow> scansForTask(UUID taskId) {
        return jdbc.query(
                """
                select scan_id, task_id, owner_entity_id, location_id, scanned_code, sku_id, batch_id,
                       condition, counted_qty, scanned_at, scanned_by, device_id, notes
                  from inventory.stock_take_scan
                 where task_id = ?
                 order by scanned_at desc
                """,
                (rs, n) -> new ScanRow(
                        rs.getObject("scan_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("scanned_code"),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("counted_qty"),
                        rs.getObject("scanned_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("scanned_by", UUID.class),
                        rs.getString("device_id"),
                        rs.getString("notes")),
                taskId);
    }

    BigDecimal totalCountedForLot(UUID taskId, UUID batchId, String condition) {
        BigDecimal sum = jdbc.queryForObject(
                """
                select coalesce(sum(counted_qty), 0)
                  from inventory.stock_take_scan
                 where task_id = ? and batch_id = ? and condition = ?
                """,
                BigDecimal.class,
                taskId,
                batchId,
                condition);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    int totalScansCount(UUID taskId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from inventory.stock_take_scan where task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    BigDecimal totalCountedUnits(UUID taskId) {
        BigDecimal sum = jdbc.queryForObject(
                "select coalesce(sum(counted_qty), 0) from inventory.stock_take_scan where task_id = ?",
                BigDecimal.class,
                taskId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    int expectedLotsCount(UUID taskId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from inventory.count_expectation where task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    List<AggregatedLotCount> aggregatedCounts(UUID taskId) {
        return jdbc.query(
                """
                select sku_id, batch_id, condition, sum(counted_qty) as total_qty, max(scanned_at) as last_scanned_at
                  from inventory.stock_take_scan
                 where task_id = ?
                 group by sku_id, batch_id, condition
                """,
                (rs, n) -> new AggregatedLotCount(
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("total_qty"),
                        rs.getObject("last_scanned_at", OffsetDateTime.class).toInstant()),
                taskId);
    }

    List<LotCandidate> lotsForSkuAtLocation(UUID locationId, UUID skuId, String condition) {
        return jdbc.query(
                """
                select batch_id, condition, qty_on_hand, expiry_date
                  from inventory.stock_lot
                 where location_id = ? and sku_id = ? and condition = ?
                 order by expiry_date nulls last, batch_id
                """,
                (rs, n) -> {
                    java.sql.Date exp = rs.getDate("expiry_date");
                    return new LotCandidate(
                            rs.getObject("batch_id", UUID.class),
                            rs.getString("condition"),
                            rs.getBigDecimal("qty_on_hand"),
                            exp == null ? null : exp.toLocalDate());
                },
                locationId,
                skuId,
                condition);
    }
}
