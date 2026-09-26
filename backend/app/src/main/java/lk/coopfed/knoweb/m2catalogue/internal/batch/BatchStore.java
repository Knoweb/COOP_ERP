package lk.coopfed.knoweb.m2catalogue.internal.batch;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of catalogue.batch, batch_key, sku and supplier for the batch handlers. Every query runs
 * inside the handler's transaction under the caller's scope, so row-level security applies: a
 * batch and a supplier are read by every scope, a SKU when it is the caller's or SHARED. The
 * writes stay in the handlers (ArchitectureTests: only a command handler writes).
 */
@Component
class BatchStore {

    static final String REGISTERED = "REGISTERED";

    /** One row of catalogue.batch. */
    record BatchRow(
            UUID batchId,
            UUID skuId,
            UUID supplierId,
            String batchNo,
            LocalDate manufactureDate,
            LocalDate expiryDate,
            BigDecimal printedMrp,
            UUID originDocumentId,
            boolean synthetic,
            UUID correctsBatchId,
            String status,
            UUID ownerEntityId) {

        boolean registered() {
            return REGISTERED.equals(status);
        }

        Map<String, Object> auditState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("batchId", batchId);
            state.put("skuId", skuId);
            state.put("supplierId", supplierId);
            state.put("batchNo", batchNo);
            state.put("manufactureDate", manufactureDate);
            state.put("expiryDate", expiryDate);
            state.put("printedMrp", printedMrp);
            state.put("originDocumentId", originDocumentId);
            state.put("synthetic", synthetic);
            state.put("correctsBatchId", correctsBatchId);
            state.put("status", status);
            state.put("ownerEntityId", ownerEntityId);
            return Collections.unmodifiableMap(state);
        }
    }

    /** What RegisterBatch needs of the SKU (22A section 6). */
    record SkuTracking(String status, boolean batchTracked, boolean expiryTracked, boolean hasPrintedMrp) {

        boolean active() {
            return "LOCAL".equals(status) || "SHARED".equals(status);
        }
    }

    private static final String SELECT =
            """
            select b.batch_id, b.sku_id, b.supplier_id, b.batch_no, b.manufacture_date, b.expiry_date,
                   b.printed_mrp, b.origin_document_id, b.is_synthetic, b.corrects_batch_id, b.status,
                   b.owner_entity_id
            from catalogue.batch b
            """;

    private static final UUID NO_SUPPLIER = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    BatchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BatchRow> findById(UUID batchId) {
        return jdbc.query(SELECT + " where b.batch_id = ?", BatchStore::map, batchId).stream()
                .findFirst();
    }

    /**
     * The batch that holds the identity (sku, supplier, batch_no) today: the first registration,
     * or its latest replacement (a correction re-points batch_key, V0003 and V0004).
     */
    Optional<BatchRow> findByIdentity(UUID skuId, UUID supplierId, String batchNo) {
        return jdbc
                .query(
                        SELECT
                                + " join catalogue.batch_key k on k.batch_id = b.batch_id"
                                + " where k.sku_id = ?"
                                + " and coalesce(k.supplier_id, '00000000-0000-0000-0000-000000000000'::uuid) = ?"
                                + " and k.batch_no = ?",
                        BatchStore::map,
                        skuId,
                        supplierId == null ? NO_SUPPLIER : supplierId,
                        batchNo)
                .stream()
                .findFirst();
    }

    /**
     * Serialises two registrations of the same identity (two GRNs confirmed together): the second
     * waits for the first to commit and then finds its batch, instead of meeting the unique key
     * of batch_key and failing its caller's whole transaction. Released at the end of the
     * transaction.
     */
    void lockIdentity(UUID skuId, UUID supplierId, String batchNo) {
        String key = skuId + "|" + (supplierId == null ? "" : supplierId) + "|" + batchNo;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, key);
    }

    Optional<SkuTracking> skuTracking(UUID skuId) {
        return jdbc
                .query(
                        "select status, batch_tracked, expiry_tracked, has_printed_mrp from catalogue.sku where sku_id = ?",
                        (rs, row) -> new SkuTracking(
                                rs.getString("status"),
                                rs.getBoolean("batch_tracked"),
                                rs.getBoolean("expiry_tracked"),
                                rs.getBoolean("has_printed_mrp")),
                        skuId)
                .stream()
                .findFirst();
    }

    Optional<String> supplierStatus(UUID supplierId) {
        return jdbc
                .queryForList("select status from catalogue.supplier where supplier_id = ?", String.class, supplierId)
                .stream()
                .findFirst();
    }

    private static BatchRow map(ResultSet rs, int row) throws SQLException {
        return new BatchRow(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("batch_no"),
                rs.getObject("manufacture_date", LocalDate.class),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getBigDecimal("printed_mrp"),
                rs.getObject("origin_document_id", UUID.class),
                rs.getBoolean("is_synthetic"),
                rs.getObject("corrects_batch_id", UUID.class),
                rs.getString("status"),
                rs.getObject("owner_entity_id", UUID.class));
    }
}
