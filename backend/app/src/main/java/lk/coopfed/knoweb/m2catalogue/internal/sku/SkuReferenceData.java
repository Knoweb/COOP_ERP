package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class SkuReferenceData {

    private final JdbcTemplate jdbc;

    SkuReferenceData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean uomExists(String code) {
        Integer count =
                jdbc.queryForObject("select count(*) from catalogue.uom where uom_code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    boolean taxCategoryExists(UUID taxCategoryId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from catalogue.tax_category where tax_category_id = ?", Integer.class, taxCategoryId);
        return count != null && count > 0;
    }

    boolean hasNonGovernedTags(UUID skuId) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                from catalogue.sku_tag st
                join catalogue.tag t on t.tag_id = st.tag_id
                where st.sku_id = ? and not t.governed
                """,
                Integer.class,
                skuId);
        return count != null && count > 0;
    }

    boolean hasCaseBarcodeWithoutConversion(UUID skuId) {
        Integer caseBarcodes = jdbc.queryForObject(
                """
                select count(*)
                from catalogue.sku_barcode
                where sku_id = ? and upper(uom_code) = 'CASE' and status = 'ACTIVE'
                """,
                Integer.class,
                skuId);
        if (caseBarcodes == null || caseBarcodes == 0) {
            return false;
        }

        Integer caseConversions = jdbc.queryForObject(
                """
                select count(*)
                from catalogue.sku_uom_conversion
                where sku_id = ? and upper(uom_code) = 'CASE'
                """,
                Integer.class,
                skuId);
        return caseConversions == null || caseConversions == 0;
    }

    boolean hasActiveBarcode(UUID skuId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from catalogue.sku_barcode where sku_id = ? and status = 'ACTIVE'",
                Integer.class,
                skuId);
        return count != null && count > 0;
    }
}
