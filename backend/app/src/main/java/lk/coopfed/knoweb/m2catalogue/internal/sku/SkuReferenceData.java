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
}
