package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.m4trading.api.DeliveryLineSummary;
import lk.coopfed.knoweb.m4trading.api.DropSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The drops of a delivery note with their lines, as the events and the queries carry them: the
 * drop rows, the extension lines (drop, order, order line, quantity) and the kernel lines (item,
 * unit, batch). Read-only.
 */
@Component
public class DeliveryReads {

    public static final String DN = "DN";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    DeliveryReads(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public List<DropSummary> drops(UUID deliveryNoteId) {
        Map<UUID, DocumentLineRecord> kernelLines = new HashMap<>();
        for (DocumentLineRecord line : documents.findLines(deliveryNoteId)) {
            kernelLines.put(line.id(), line);
        }
        Map<UUID, List<DeliveryLineSummary>> linesByDrop = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select line_id, drop_id, order_id, order_line_id, dispatched_qty
                  from trading.doc_delivery_line where document_id = ?
                """,
                deliveryNoteId)) {
            UUID lineId = (UUID) row.get("line_id");
            DocumentLineRecord kernel = kernelLines.get(lineId);
            linesByDrop
                    .computeIfAbsent((UUID) row.get("drop_id"), drop -> new ArrayList<>())
                    .add(new DeliveryLineSummary(
                            lineId,
                            kernel == null ? 0 : kernel.lineNo(),
                            (UUID) row.get("order_id"),
                            (UUID) row.get("order_line_id"),
                            kernel == null ? null : kernel.skuId(),
                            kernel == null ? null : kernel.batchId(),
                            kernel == null ? null : kernel.uomCode(),
                            (BigDecimal) row.get("dispatched_qty")));
        }
        List<DropSummary> drops = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select drop_id, seq, ship_to_location_id, bill_to_entity_id, order_ids
                  from trading.doc_delivery_drop where document_id = ? order by seq
                """,
                deliveryNoteId)) {
            UUID dropId = (UUID) row.get("drop_id");
            List<DeliveryLineSummary> lines = new ArrayList<>(linesByDrop.getOrDefault(dropId, List.of()));
            lines.sort((a, b) -> Integer.compare(a.lineNo(), b.lineNo()));
            drops.add(new DropSummary(
                    dropId,
                    ((Number) row.get("seq")).intValue(),
                    (UUID) row.get("ship_to_location_id"),
                    (UUID) row.get("bill_to_entity_id"),
                    uuids(row.get("order_ids")),
                    List.copyOf(lines)));
        }
        return drops;
    }

    public List<UUID> orderIds(List<DropSummary> drops) {
        return drops.stream().flatMap(drop -> drop.orderIds().stream()).distinct().toList();
    }

    static List<UUID> uuids(Object array) {
        try {
            if (array instanceof java.sql.Array sql) {
                return Arrays.stream((Object[]) sql.getArray()).map(UUID.class::cast).toList();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return List.of();
    }
}
