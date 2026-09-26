package lk.coopfed.knoweb.m2catalogue.internal.snapshot;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The catalogue in a shop's till snapshot (22A section 7.3, CatalogueSnapshotContributor; doc 32
 * section 5.1, "SKUs in the shop's assortment ... with units, conversions, barcodes, tags, tax
 * categories").
 *
 * <p>Snapshot tables:
 *
 * <ul>
 *   <li>{@code sku} (row id = SKU id): the item as the till sells it, with its conversions in
 *       force, its ACTIVE barcodes, its tag codes and its thumbnail keys (M2-06) inside the row. 22A lists those as tables of
 *       their own, but their rows have no id of their own to name in the change log (the keys are
 *       SKU and unit, or barcode and symbology), and the till needs the whole item at once:
 *       "UPSERT rows so the till receives the whole item" (22A section 7.2). So a change to any
 *       of them is an upsert of the SKU.
 *   <li>{@code tax_category} (row id = category id): the category with its rates in force or
 *       scheduled; a scheduled rate carries its own effective_from.
 * </ul>
 *
 * <p>Which SKUs: until the assortment of M2-09 exists, every SKU the shop's entity may sell, its
 * own LOCAL ones and every SHARED one (row-level security in the device's scope shows exactly
 * those). A DRAFT or INACTIVE SKU is not returned, so the till receives a tombstone for it.
 * {@code batch} is M5's lot contributor's to name (22A: "ids supplied by M5") and is not served
 * yet.
 */
@Component
class CatalogueSnapshotContributor implements SnapshotContributor {

    static final String SKU = "sku";
    static final String TAX_CATEGORY = "tax_category";

    private final NamedParameterJdbcTemplate jdbc;

    CatalogueSnapshotContributor(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Set<String> tables() {
        return Set.of(SKU, TAX_CATEGORY);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return Map.of();
        }
        return read(table, shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        return read(table, shop, null);
    }

    private Map<UUID, Map<String, Object>> read(String table, Shop shop, Collection<UUID> ids) {
        return switch (table) {
            case SKU -> skus(shop, ids);
            case TAX_CATEGORY -> taxCategories(ids);
            default -> throw new IllegalArgumentException("Not an M2 snapshot table: " + table);
        };
    }

    // ---- sku ----------------------------------------------------------------------------------

    private Map<UUID, Map<String, Object>> skus(Shop shop, Collection<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        String onlyIds = ids == null ? "" : " and s.sku_id in (:ids)";

        Map<UUID, Map<String, Object>> skus = new LinkedHashMap<>();
        jdbc.query(
                """
                select s.sku_id, s.sku_code, s.status, s.short_name_en, s.short_name_si, s.short_name_ta,
                       s.base_uom_code, s.sold_by_weight, s.batch_tracked, s.expiry_tracked, s.has_printed_mrp,
                       s.expiry_warning_days, s.tax_category_id, s.multi_mrp_policy
                  from catalogue.sku s
                 where s.status in ('LOCAL', 'SHARED')"""
                        + onlyIds
                        + " order by s.sku_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sku_code", rs.getString("sku_code"));
                    row.put("status", rs.getString("status"));
                    row.put("short_name_en", rs.getString("short_name_en"));
                    row.put("short_name_si", rs.getString("short_name_si"));
                    row.put("short_name_ta", rs.getString("short_name_ta"));
                    row.put("base_uom_code", rs.getString("base_uom_code"));
                    row.put("sold_by_weight", rs.getBoolean("sold_by_weight"));
                    row.put("batch_tracked", rs.getBoolean("batch_tracked"));
                    row.put("expiry_tracked", rs.getBoolean("expiry_tracked"));
                    row.put("has_printed_mrp", rs.getBoolean("has_printed_mrp"));
                    row.put("expiry_warning_days", (Integer) rs.getObject("expiry_warning_days", Integer.class));
                    row.put("tax_category_id", rs.getString("tax_category_id"));
                    row.put("multi_mrp_policy", rs.getString("multi_mrp_policy"));
                    row.put("conversions", new ArrayList<Map<String, Object>>());
                    row.put("barcodes", new ArrayList<Map<String, Object>>());
                    row.put("tags", new ArrayList<String>());
                    row.put("images", new ArrayList<Map<String, Object>>());
                    skus.put(rs.getObject("sku_id", UUID.class), row);
                });
        if (skus.isEmpty()) {
            return skus;
        }
        MapSqlParameterSource found = new MapSqlParameterSource("skus", skus.keySet());

        // Conversions in force today or later (a new case size is a new dated row, doc 22 3.2).
        jdbc.query(
                """
                select sku_id, uom_code, factor_to_base, effective_from, effective_to
                  from catalogue.sku_uom_conversion
                 where sku_id in (:skus) and (effective_to is null or effective_to >= current_date)
                 order by sku_id, uom_code, effective_from
                """,
                found,
                rs -> {
                    Map<String, Object> conversion = new LinkedHashMap<>();
                    conversion.put("uom_code", rs.getString("uom_code"));
                    conversion.put("factor_to_base", text(rs.getBigDecimal("factor_to_base")));
                    conversion.put("effective_from", text(rs.getDate("effective_from")));
                    conversion.put("effective_to", text(rs.getDate("effective_to")));
                    listOf(skus, rs.getObject("sku_id", UUID.class), "conversions")
                            .add(conversion);
                });

        jdbc.query(
                """
                select sku_id, barcode, symbology, uom_code, batch_id
                  from catalogue.sku_barcode
                 where sku_id in (:skus) and status = 'ACTIVE'
                 order by sku_id, barcode, symbology
                """,
                found,
                rs -> {
                    Map<String, Object> barcode = new LinkedHashMap<>();
                    barcode.put("barcode", rs.getString("barcode"));
                    barcode.put("symbology", rs.getString("symbology"));
                    barcode.put("uom_code", rs.getString("uom_code"));
                    barcode.put("batch_id", rs.getString("batch_id"));
                    listOf(skus, rs.getObject("sku_id", UUID.class), "barcodes").add(barcode);
                });

        jdbc.query(
                "select sku_id, tag_code from catalogue.sku_tag where sku_id in (:skus) order by sku_id, tag_code",
                found,
                rs -> {
                    listOf(skus, rs.getObject("sku_id", UUID.class), "tags").add(rs.getString("tag_code"));
                });

        // The thumbnails the shop shows (M2-06; 22A section 7.3: "images = thumb keys only"): one
        // per item and per pack, the shop entity's own local override before the SKU owner's
        // image (doc 22 section 3.4, DR-5). Row-level security in the device's scope shows the
        // entity's own images and the owner's images of a SHARED item, nobody else's override.
        MapSqlParameterSource images = new MapSqlParameterSource("skus", skus.keySet())
                .addValue("entity", shop == null ? null : shop.ownerEntityId());
        jdbc.query(
                """
                select distinct on (sku_id, coalesce(barcode, ''))
                       sku_id, barcode, object_key_thumb
                  from catalogue.sku_image
                 where sku_id in (:skus) and status = 'ACTIVE'
                 order by sku_id, coalesce(barcode, ''),
                          case when owner_entity_id = :entity then 0 else 1 end
                """,
                images,
                rs -> {
                    Map<String, Object> image = new LinkedHashMap<>();
                    image.put("barcode", rs.getString("barcode"));
                    image.put("thumb_key", rs.getString("object_key_thumb"));
                    listOf(skus, rs.getObject("sku_id", UUID.class), "images").add(image);
                });
        return skus;
    }

    // ---- tax_category -------------------------------------------------------------------------

    private Map<UUID, Map<String, Object>> taxCategories(Collection<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        String onlyIds = ids == null ? "" : " where tax_category_id in (:ids)";

        Map<UUID, Map<String, Object>> categories = new LinkedHashMap<>();
        jdbc.query(
                "select tax_category_id, code, name_en, name_si, name_ta from catalogue.tax_category"
                        + onlyIds
                        + " order by tax_category_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", rs.getString("code"));
                    row.put("name_en", rs.getString("name_en"));
                    row.put("name_si", rs.getString("name_si"));
                    row.put("name_ta", rs.getString("name_ta"));
                    row.put("rates", new ArrayList<Map<String, Object>>());
                    categories.put(rs.getObject("tax_category_id", UUID.class), row);
                });
        if (categories.isEmpty()) {
            return categories;
        }

        // Rates in force or scheduled (22A section 7.3: "all rows with effective_to null or future").
        jdbc.query(
                """
                select tax_category_id, rate_percent, effective_from, effective_to
                  from catalogue.tax_rate
                 where tax_category_id in (:categories) and (effective_to is null or effective_to >= current_date)
                 order by tax_category_id, effective_from
                """,
                new MapSqlParameterSource("categories", categories.keySet()),
                rs -> {
                    Map<String, Object> rate = new LinkedHashMap<>();
                    rate.put("rate_percent", text(rs.getBigDecimal("rate_percent")));
                    rate.put("effective_from", text(rs.getDate("effective_from")));
                    rate.put("effective_to", text(rs.getDate("effective_to")));
                    listOf(categories, rs.getObject("tax_category_id", UUID.class), "rates")
                            .add(rate);
                });
        return categories;
    }

    // ---- helpers ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> List<T> listOf(Map<UUID, Map<String, Object>> rows, UUID id, String field) {
        return (List<T>) rows.get(id).get(field);
    }

    /** Decimals travel as text (SnapshotContributor): the till must not round them. */
    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String text(Date date) {
        return date == null ? null : date.toLocalDate().toString();
    }
}
