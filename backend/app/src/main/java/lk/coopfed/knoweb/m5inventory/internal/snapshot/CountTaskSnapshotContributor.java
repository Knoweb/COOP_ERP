package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Snapshot contributor for open inventory count tasks at a location (doc 25A section 7.3).
 *
 * <p>Serves the {@code count_task} snapshot table:
 * <ul>
 *   <li>Only open tasks ({@code SCHEDULED} and {@code COUNTING}) for the requesting location.
 *   <li>SCHEDULED tasks include empty expectations list {@code []}.
 *   <li>COUNTING tasks include frozen expectations from {@code inventory.count_expectation}.
 *   <li>Completed or reviewed counts ({@code VARIANCE_REVIEW}, {@code CLOSED}) are excluded,
 *       producing tombstones on the till.
 * </ul>
 */
@Component
public class CountTaskSnapshotContributor implements SnapshotContributor {

    public static final String TABLE = "count_task";

    private final JdbcTemplate jdbc;

    CountTaskSnapshotContributor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> tables() {
        return Set.of(TABLE);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (!TABLE.equals(table) || shop == null || rowIds == null || rowIds.isEmpty()) {
            return Map.of();
        }
        return read(shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        if (!TABLE.equals(table) || shop == null) {
            return Map.of();
        }
        return read(shop, null);
    }

    private Map<UUID, Map<String, Object>> read(Shop shop, Collection<UUID> rowIds) {
        String taskSql =
                """
                select t.task_id, t.scope_kind, t.scope_sku_ids, t.scheduled_for, t.status
                  from inventory.count_task t
                 where t.location_id = ?
                   and t.status in ('SCHEDULED', 'COUNTING')
                """
                        + (rowIds == null ? "" : " and t.task_id = any(?)")
                        + " order by t.scheduled_for, t.task_id";

        record TaskHeader(UUID taskId, String scopeKind, List<String> scopeSkuIds, Date scheduledFor, String status) {}

        List<TaskHeader> headers = jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(taskSql);
                    ps.setObject(1, shop.locationId());
                    if (rowIds != null) {
                        ps.setArray(2, uuids(connection, rowIds));
                    }
                    return ps;
                },
                (rs, rowNum) -> {
                    UUID taskId = rs.getObject("task_id", UUID.class);
                    String scopeKind = rs.getString("scope_kind");
                    Array skuArray = rs.getArray("scope_sku_ids");
                    List<String> scopeSkuIds = new ArrayList<>();
                    if (skuArray != null) {
                        UUID[] skus = (UUID[]) skuArray.getArray();
                        if (skus != null) {
                            for (UUID u : skus) {
                                scopeSkuIds.add(u.toString());
                            }
                        }
                    }
                    Date scheduledFor = rs.getDate("scheduled_for");
                    String status = rs.getString("status");
                    return new TaskHeader(taskId, scopeKind, scopeSkuIds, scheduledFor, status);
                });

        if (headers.isEmpty()) {
            return Map.of();
        }

        List<UUID> taskIds = headers.stream().map(TaskHeader::taskId).toList();
        Map<UUID, List<Map<String, Object>>> expectationsByTask = loadExpectations(taskIds);

        Map<UUID, Map<String, Object>> tasks = new LinkedHashMap<>();
        for (TaskHeader header : headers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task_id", header.taskId().toString());
            row.put("scope_kind", header.scopeKind());
            row.put("scope_sku_ids", header.scopeSkuIds());
            row.put("scheduled_for", header.scheduledFor().toString());
            row.put("status", header.status());
            row.put("expectations", expectationsByTask.getOrDefault(header.taskId(), List.of()));

            tasks.put(header.taskId(), row);
        }
        return tasks;
    }

    private Map<UUID, List<Map<String, Object>>> loadExpectations(List<UUID> taskIds) {
        String expectationSql =
                """
                select e.task_id, e.batch_id, e.sku_id, e.condition, e.expected_qty
                  from inventory.count_expectation e
                 where e.task_id = any(?)
                 order by e.task_id, e.sku_id, e.batch_id
                """;

        return jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(expectationSql);
                    ps.setArray(1, uuids(connection, taskIds));
                    return ps;
                },
                rs -> {
                    Map<UUID, List<Map<String, Object>>> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        UUID taskId = rs.getObject("task_id", UUID.class);
                        UUID batchId = rs.getObject("batch_id", UUID.class);
                        UUID skuId = rs.getObject("sku_id", UUID.class);
                        String condition = rs.getString("condition");
                        BigDecimal qty = rs.getBigDecimal("expected_qty");

                        Map<String, Object> exp = new LinkedHashMap<>();
                        exp.put("batch_id", batchId.toString());
                        exp.put("sku_id", skuId.toString());
                        exp.put("condition", condition);
                        exp.put(
                                "expected_qty",
                                qty == null
                                        ? "0.000"
                                        : qty.setScale(3, RoundingMode.HALF_UP).toPlainString());

                        map.computeIfAbsent(taskId, k -> new ArrayList<>()).add(exp);
                    }
                    return map;
                });
    }

    private static Array uuids(Connection c, Collection<UUID> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.toArray(new UUID[0]));
    }
}
