package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 * Snapshot contributor for in-transit expected transfers at a destination shop (doc 25A section 7.3).
 *
 * <p>Serves the {@code expected_transfer} snapshot table:
 * <ul>
 *   <li>Only dispatched, in-transit transfers addressed to the requesting location ({@code
 *       to_location_id}).
 *   <li>Transfers already received (a receipt row exists) are excluded, producing tombstones.
 *   <li>Includes line items (line ID, line number, batch ID, SKU ID, quantity).
 *   <li>Unit costs are strictly excluded (data minimization / security rule).
 * </ul>
 */
@Component
public class ExpectedTransferContributor implements SnapshotContributor {

    public static final String TABLE = "expected_transfer";

    private final JdbcTemplate jdbc;

    ExpectedTransferContributor(JdbcTemplate jdbc) {
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
        String transferSql =
                """
                select t.transfer_id, t.location_id as from_location_id, t.to_location_id, t.issued_at
                  from inventory.transfer t
                  join inventory.transfer_dispatch d on d.transfer_id = t.transfer_id
                 where t.to_location_id = ?
                   and not exists (
                       select 1 from inventory.transfer_receipt r
                        where r.transfer_id = t.transfer_id
                   )
                """
                        + (rowIds == null ? "" : " and t.transfer_id = any(?)")
                        + " order by t.issued_at, t.transfer_id";

        record TransferHeader(UUID transferId, UUID fromLocationId, UUID toLocationId, Timestamp issuedAt) {}

        List<TransferHeader> headers = jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(transferSql);
                    ps.setObject(1, shop.locationId());
                    if (rowIds != null) {
                        ps.setArray(2, uuids(connection, rowIds));
                    }
                    return ps;
                },
                (rs, rowNum) -> new TransferHeader(
                        rs.getObject("transfer_id", UUID.class),
                        rs.getObject("from_location_id", UUID.class),
                        rs.getObject("to_location_id", UUID.class),
                        rs.getTimestamp("issued_at")));

        if (headers.isEmpty()) {
            return Map.of();
        }

        List<UUID> transferIds =
                headers.stream().map(TransferHeader::transferId).toList();
        Map<UUID, List<Map<String, Object>>> linesByTransfer = loadLines(transferIds);

        Map<UUID, Map<String, Object>> transfers = new LinkedHashMap<>();
        for (TransferHeader header : headers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transfer_id", header.transferId().toString());
            row.put("from_location_id", header.fromLocationId().toString());
            row.put("to_location_id", header.toLocationId().toString());
            row.put("issued_at", header.issuedAt().toInstant().toString());
            row.put("lines", linesByTransfer.getOrDefault(header.transferId(), List.of()));

            transfers.put(header.transferId(), row);
        }
        return transfers;
    }

    private Map<UUID, List<Map<String, Object>>> loadLines(List<UUID> transferIds) {
        String lineSql =
                """
                select l.line_id, l.transfer_id, l.line_no, l.batch_id, l.sku_id, l.qty
                  from inventory.transfer_line l
                 where l.transfer_id = any(?)
                 order by l.transfer_id, l.line_no
                """;

        return jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(lineSql);
                    ps.setArray(1, uuids(connection, transferIds));
                    return ps;
                },
                rs -> {
                    Map<UUID, List<Map<String, Object>>> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        UUID lineId = rs.getObject("line_id", UUID.class);
                        UUID transferId = rs.getObject("transfer_id", UUID.class);
                        int lineNo = rs.getInt("line_no");
                        UUID batchId = rs.getObject("batch_id", UUID.class);
                        UUID skuId = rs.getObject("sku_id", UUID.class);
                        BigDecimal qty = rs.getBigDecimal("qty");

                        Map<String, Object> line = new LinkedHashMap<>();
                        line.put("line_id", lineId.toString());
                        line.put("line_no", lineNo);
                        line.put("batch_id", batchId.toString());
                        line.put("sku_id", skuId.toString());
                        line.put(
                                "qty",
                                qty == null
                                        ? "0.000"
                                        : qty.setScale(3, RoundingMode.HALF_UP).toPlainString());

                        map.computeIfAbsent(transferId, k -> new ArrayList<>()).add(line);
                    }
                    return map;
                });
    }

    private static Array uuids(Connection c, Collection<UUID> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.toArray(new UUID[0]));
    }
}
