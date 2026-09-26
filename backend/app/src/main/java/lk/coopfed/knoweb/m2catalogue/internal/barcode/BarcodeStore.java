package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of catalogue.sku_barcode, catalogue.uom and catalogue.batch for the barcode handlers.
 * Every query runs inside the handler's transaction, under the caller's scope, so row-level
 * security applies: the caller sees its own rows and those of SHARED items. The writes stay in
 * the handlers: only a command handler writes (ArchitectureTests).
 */
@Component
class BarcodeStore {

    /** One row of catalogue.sku_barcode; the key is (barcode, symbology, owner_entity_id). */
    record BarcodeRow(
            String barcode,
            String symbology,
            UUID skuId,
            String uomCode,
            UUID batchId,
            UUID ownerEntityId,
            String status) {

        boolean active() {
            return BarcodeGuards.ACTIVE.equals(status);
        }

        Map<String, Object> auditState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("barcode", barcode);
            state.put("symbology", symbology);
            state.put("skuId", skuId);
            state.put("uomCode", uomCode);
            state.put("batchId", batchId);
            state.put("ownerEntityId", ownerEntityId);
            state.put("status", status);
            return Collections.unmodifiableMap(state);
        }
    }

    private static final String SELECT =
            """
            select barcode, symbology, sku_id, uom_code, batch_id, owner_entity_id, status
            from catalogue.sku_barcode
            """;

    private final JdbcTemplate jdbc;

    BarcodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The caller's own registry row of the code: the one Retire and Link change. */
    Optional<BarcodeRow> findOwn(String barcode, String symbology, UUID ownerEntityId) {
        return jdbc
                .query(
                        SELECT + " where barcode = ? and symbology = ? and owner_entity_id = ?",
                        BarcodeStore::map,
                        barcode,
                        symbology,
                        ownerEntityId)
                .stream()
                .findFirst();
    }

    /**
     * An ACTIVE row of a factory code visible in the scope (B-I2: unique federation-wide). Rows
     * of another entity's LOCAL item are hidden by RLS; the unique index barcode_factory_unique
     * is the backstop for those.
     */
    boolean activeFactoryCodeExists(String barcode, String symbology) {
        Integer count = jdbc.queryForObject(
                "select count(*) from catalogue.sku_barcode"
                        + " where barcode = ? and symbology = ? and symbology <> 'INTERNAL' and status = 'ACTIVE'",
                Integer.class,
                barcode,
                symbology);
        return count != null && count > 0;
    }

    boolean uomExists(String uomCode) {
        Integer count =
                jdbc.queryForObject("select count(*) from catalogue.uom where uom_code = ?", Integer.class, uomCode);
        return count != null && count > 0;
    }

    /** A batch is global identity, readable by every scope (22A section 3). */
    boolean batchBelongsToSku(UUID batchId, UUID skuId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from catalogue.batch where batch_id = ? and sku_id = ?",
                Integer.class,
                batchId,
                skuId);
        return count != null && count > 0;
    }

    private static BarcodeRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BarcodeRow(
                rs.getString("barcode"),
                rs.getString("symbology"),
                rs.getObject("sku_id", UUID.class),
                rs.getString("uom_code"),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("status"));
    }
}
