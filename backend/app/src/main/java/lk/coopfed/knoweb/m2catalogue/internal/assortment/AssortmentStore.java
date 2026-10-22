package lk.coopfed.knoweb.m2catalogue.internal.assortment;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Data access for catalogue.location_assortment (22A section 3, section 7.2).
 */
@Repository
public class AssortmentStore {

    private final JdbcTemplate jdbc;

    public AssortmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssortmentRecord> get(UUID locationId, UUID skuId) {
        List<AssortmentRecord> results = jdbc.query(
                """
                SELECT location_id, sku_id, reasons, sell_through, since, owner_entity_id
                  FROM catalogue.location_assortment
                 WHERE location_id = ? AND sku_id = ?
                   AND (cardinality(reasons) > 0 OR sell_through = true)
                """,
                AssortmentStore::mapRow,
                locationId,
                skuId);
        return results.stream().findFirst();
    }

    public List<AssortmentRecord> findByLocation(UUID locationId) {
        return jdbc.query(
                """
                SELECT location_id, sku_id, reasons, sell_through, since, owner_entity_id
                  FROM catalogue.location_assortment
                 WHERE location_id = ?
                   AND (cardinality(reasons) > 0 OR sell_through = true)
                 ORDER BY sku_id
                """,
                AssortmentStore::mapRow,
                locationId);
    }

    public List<AssortmentRecord> findBySku(UUID skuId) {
        return jdbc.query(
                """
                SELECT location_id, sku_id, reasons, sell_through, since, owner_entity_id
                  FROM catalogue.location_assortment
                 WHERE sku_id = ?
                   AND (cardinality(reasons) > 0 OR sell_through = true)
                 ORDER BY location_id
                """,
                AssortmentStore::mapRow,
                skuId);
    }

    public List<AssortmentRecord> findBySkuAndReason(UUID skuId, String reason) {
        return jdbc.query(
                """
                SELECT location_id, sku_id, reasons, sell_through, since, owner_entity_id
                  FROM catalogue.location_assortment
                 WHERE sku_id = ? AND ? = ANY(reasons)
                   AND (cardinality(reasons) > 0 OR sell_through = true)
                 ORDER BY location_id
                """,
                AssortmentStore::mapRow,
                skuId,
                reason);
    }

    private static AssortmentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID locationId = rs.getObject("location_id", UUID.class);
        UUID skuId = rs.getObject("sku_id", UUID.class);
        Array reasonsArr = rs.getArray("reasons");
        List<String> reasons = new ArrayList<>();
        if (reasonsArr != null) {
            String[] arr = (String[]) reasonsArr.getArray();
            if (arr != null) {
                reasons = List.of(arr);
            }
        }
        boolean sellThrough = rs.getBoolean("sell_through");
        Timestamp ts = rs.getTimestamp("since");
        Instant since = ts != null ? ts.toInstant() : Instant.EPOCH;
        UUID ownerEntityId = rs.getObject("owner_entity_id", UUID.class);

        return new AssortmentRecord(locationId, skuId, reasons, sellThrough, since, ownerEntityId);
    }
}
