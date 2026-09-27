package lk.coopfed.knoweb.m4trading.internal.grn;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A GRN with its lines: the kernel header and lines and M4's extension rows. Read-only. */
@Component
public class GrnReads {

    public static final String GRN = "GRN";
    public static final String DISC = "DISC";
    public static final String CONFIRMED = "CONFIRMED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    GrnReads(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public Optional<Grn> grn(UUID grnId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select receiver_entity_id, receiver_location_id, seller_entity_id, relationship_id, drop_id,
                       delivery_document_id, supplier_id, received_on, confirmed_at
                  from trading.doc_grn where document_id = ?
                """,
                grnId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Optional<DocumentRecord> header = documents.findById(grnId);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        Map<UUID, Map<String, Object>> extension = new HashMap<>();
        for (Map<String, Object> line : jdbc.queryForList(
                """
                select line_id, expected_qty, received_qty, damaged_qty, batch_no, manufacture_date, expiry_date,
                       printed_mrp, unit_cost, batch_id
                  from trading.doc_grn_line where document_id = ?
                """,
                grnId)) {
            extension.put((UUID) line.get("line_id"), line);
        }
        List<GrnLine> lines = new ArrayList<>();
        for (DocumentLineRecord kernel : documents.findLines(grnId)) {
            Map<String, Object> ext = extension.getOrDefault(kernel.id(), Map.of());
            lines.add(new GrnLine(
                    kernel.id(),
                    kernel.lineNo(),
                    kernel.skuId(),
                    kernel.uomCode(),
                    (BigDecimal) ext.get("expected_qty"),
                    (BigDecimal) ext.get("received_qty"),
                    (BigDecimal) ext.get("damaged_qty"),
                    (String) ext.get("batch_no"),
                    date(ext.get("manufacture_date")),
                    date(ext.get("expiry_date")),
                    (BigDecimal) ext.get("printed_mrp"),
                    ext.get("unit_cost") != null ? (BigDecimal) ext.get("unit_cost") : kernel.unitCostAtIssue(),
                    (UUID) ext.get("batch_id")));
        }
        return Optional.of(new Grn(
                header.get(),
                (UUID) row.get("receiver_entity_id"),
                (UUID) row.get("receiver_location_id"),
                (UUID) row.get("seller_entity_id"),
                (UUID) row.get("relationship_id"),
                (UUID) row.get("drop_id"),
                (UUID) row.get("delivery_document_id"),
                (UUID) row.get("supplier_id"),
                date(row.get("received_on")),
                row.get("confirmed_at") instanceof Timestamp ts ? ts.toInstant() : null,
                lines));
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : null;
    }

    public record Grn(
            DocumentRecord header,
            UUID receiverEntityId,
            UUID receiverLocationId,
            UUID sellerEntityId,
            UUID relationshipId,
            UUID dropId,
            UUID deliveryDocumentId,
            UUID supplierId,
            LocalDate receivedOn,
            Instant confirmedAt,
            List<GrnLine> lines) {}

    public record GrnLine(
            UUID lineId,
            int lineNo,
            UUID skuId,
            String uomCode,
            BigDecimal expectedQty,
            BigDecimal receivedQty,
            BigDecimal damagedQty,
            String batchNo,
            LocalDate manufactureDate,
            LocalDate expiryDate,
            BigDecimal printedMrp,
            BigDecimal unitCost,
            UUID batchId) {}
}
