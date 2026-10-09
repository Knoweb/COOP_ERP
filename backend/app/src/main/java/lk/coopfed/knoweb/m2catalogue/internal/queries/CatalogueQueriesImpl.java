package lk.coopfed.knoweb.m2catalogue.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.ConversionView;
import lk.coopfed.knoweb.m2catalogue.query.ImageView;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m2catalogue.query.TaxCategoryView;
import lk.coopfed.knoweb.m2catalogue.query.TaxRateView;
import lk.coopfed.knoweb.m2catalogue.query.UomView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CatalogueQueriesImpl implements CatalogueQueries {

    private static final String SELECT =
            """
            select
                sku_id,
                sku_code,
                owner_entity_id,
                status,
                short_name_en,
                short_name_si,
                short_name_ta,
                description_en,
                description_si,
                description_ta,
                base_uom_code,
                sold_by_weight,
                batch_tracked,
                expiry_tracked,
                has_printed_mrp,
                expiry_warning_days,
                tax_category_id,
                multi_mrp_policy,
                origin_kind,
                attributes::text as attributes_json
            from catalogue.sku
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BarcodeLookupQuery barcodes;

    CatalogueQueriesImpl(JdbcTemplate jdbc, ObjectMapper mapper, BarcodeLookupQuery barcodes) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.barcodes = barcodes;
    }

    @Override
    public Optional<LookupResult> lookupByBarcode(BarcodeLookup lookup, ScopeContext scope) {
        return barcodes.lookup(lookup, scope);
    }

    @Override
    public List<UomView> units(ScopeContext scope) {
        return jdbc.query(
                "select uom_code, name_en, name_si, name_ta, is_weight from catalogue.uom order by uom_code",
                (rs, row) -> new UomView(
                        rs.getString("uom_code"),
                        rs.getString("name_en"),
                        rs.getString("name_si"),
                        rs.getString("name_ta"),
                        rs.getBoolean("is_weight")));
    }

    @Override
    public List<TaxCategoryView> taxCategories(ScopeContext scope) {
        return jdbc.query(
                "select tax_category_id, code, name_en, name_si, name_ta from catalogue.tax_category order by code",
                (rs, row) -> new TaxCategoryView(
                        rs.getObject("tax_category_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name_en"),
                        rs.getString("name_si"),
                        rs.getString("name_ta")));
    }

    @Override
    public Optional<TaxRateView> taxRateInForce(UUID skuId, LocalDate onDate, ScopeContext scope) {
        if (skuId == null || onDate == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                        select s.sku_id, c.tax_category_id, c.code, r.rate_percent, r.effective_from
                        from catalogue.sku s
                        join catalogue.tax_category c on c.tax_category_id = s.tax_category_id
                        join catalogue.tax_rate r on r.tax_category_id = c.tax_category_id
                        where s.sku_id = ?
                          and r.effective_from <= ?
                          and (r.effective_to is null or r.effective_to >= ?)
                        order by r.effective_from desc
                        limit 1
                        """,
                        (rs, row) -> new TaxRateView(
                                rs.getObject("sku_id", UUID.class),
                                rs.getObject("tax_category_id", UUID.class),
                                rs.getString("code"),
                                rs.getBigDecimal("rate_percent"),
                                rs.getObject("effective_from", LocalDate.class)),
                        skuId,
                        onDate,
                        onDate)
                .stream()
                .findFirst();
    }

    @Override
    public List<ConversionView> conversions(UUID skuId, ScopeContext scope) {
        if (skuId == null || scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        return jdbc.query(
                """
                select uom_code, factor_to_base, effective_from, effective_to
                from catalogue.sku_uom_conversion
                where sku_id = ?
                order by uom_code, effective_from desc
                """,
                (rs, row) -> new ConversionView(
                        rs.getString("uom_code"),
                        rs.getBigDecimal("factor_to_base"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)),
                skuId);
    }

    @Override
    public List<BarcodeView> barcodes(UUID skuId, ScopeContext scope) {
        if (skuId == null || scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        return jdbc.query(
                """
                select barcode, symbology, uom_code, batch_id, status
                from catalogue.sku_barcode
                where sku_id = ?
                order by status, barcode
                """,
                (rs, row) -> new BarcodeView(
                        rs.getString("barcode"),
                        rs.getString("symbology"),
                        rs.getString("uom_code"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("status")),
                skuId);
    }

    @Override
    public List<ImageView> images(UUID skuId, ScopeContext scope) {
        if (skuId == null || scope == null || !scope.hasActiveScope()) {
            return List.of();
        }
        return jdbc.query(
                """
                select image_id, barcode, status, object_key_full, object_key_thumb, content_type
                from catalogue.sku_image
                where sku_id = ?
                order by created_at desc
                """,
                (rs, row) -> new ImageView(
                        rs.getObject("image_id", UUID.class),
                        rs.getString("barcode"),
                        rs.getString("status"),
                        rs.getString("object_key_full"),
                        rs.getString("object_key_thumb"),
                        rs.getString("content_type")),
                skuId);
    }

    @Override
    public Optional<SkuView> getSku(UUID skuId, ScopeContext scope) {
        if (skuId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }

        return jdbc.query(SELECT + " where sku_id = ?", (rs, row) -> map(rs, scope), skuId).stream()
                .findFirst();
    }

    @Override
    public SkuPage listSkus(SkuFilter filter, ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return new SkuPage(List.of(), null);
        }

        SkuFilter effective = filter == null ? new SkuFilter(null, null, "en", 0, 50) : filter;

        return execute(effective, scope, false);
    }

    @Override
    public SkuPage searchSku(SkuFilter filter, ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope()) {
            return new SkuPage(List.of(), null);
        }

        SkuFilter effective = filter == null ? new SkuFilter(null, null, "en", 0, 50) : filter;

        return execute(effective, scope, true);
    }

    private SkuPage execute(SkuFilter filter, ScopeContext scope, boolean search) {
        int offset = filter.normalizedOffset();
        int limit = filter.normalizedLimit();
        int fetchLimit = limit + 1;

        String displayColumn = displayColumn(filter.language());

        StringBuilder sql = new StringBuilder(SELECT + " where 1 = 1");
        List<Object> params = new ArrayList<>();

        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" and status = ?");
            params.add(filter.status().strip().toUpperCase());
        }

        boolean searching = search && filter.query() != null && !filter.query().isBlank();
        String query = searching ? filter.query().strip() : null;

        if (searching) {
            // 22A section 7: the three names by trigram, or a prefix of the code. The name columns
            // stay bare so that their gin_trgm_ops indexes apply (a null name simply does not
            // match), and the user's text is escaped so that % and _ are letters, not wildcards.
            String escaped = escapeLike(query);
            String contains = "%" + escaped + "%";
            sql.append(
                    """
                     and (
                         sku_code like ? escape '\\'
                         or short_name_en ilike ? escape '\\'
                         or short_name_si ilike ? escape '\\'
                         or short_name_ta ilike ? escape '\\'
                     )
                    """);
            params.add(escaped.toUpperCase() + "%");
            params.add(contains);
            params.add(contains);
            params.add(contains);
        }

        sql.append(" order by ");
        sql.append(displayColumn);

        if (searching) {
            // Then the closest match first among equal names (22A section 7: collation, then similarity).
            sql.append(
                    """
                    , greatest(
                          kernel.similarity(short_name_en, ?),
                          coalesce(kernel.similarity(short_name_si, ?), 0),
                          coalesce(kernel.similarity(short_name_ta, ?), 0)
                      ) desc
                    """);
            params.add(query);
            params.add(query);
            params.add(query);
        }

        sql.append(", sku_code, sku_id limit ? offset ?");

        params.add(fetchLimit);
        params.add(offset);

        List<SkuView> rows = jdbc.query(sql.toString(), (rs, row) -> map(rs, scope), params.toArray());

        boolean hasMore = rows.size() > limit;
        List<SkuView> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);

        boolean nextWithinCap = offset + limit <= SkuFilter.MAX_OFFSET;

        return new SkuPage(items, hasMore && nextWithinCap ? offset + limit : null);
    }

    /** Makes the backslash, % and _ of the user's text literal in a LIKE pattern with escape '\'. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String displayColumn(String language) {
        if (language == null) {
            return "short_name_en";
        }

        return switch (language.strip().toLowerCase()) {
            case "si" -> "coalesce(short_name_si COLLATE kernel.si_icu, short_name_en COLLATE kernel.si_icu)";
            case "ta" -> "coalesce(short_name_ta COLLATE kernel.ta_icu, short_name_en COLLATE kernel.ta_icu)";
            default -> "short_name_en COLLATE kernel.en_icu";
        };
    }

    private SkuView map(ResultSet rs, ScopeContext scope) throws SQLException {
        UUID owner = rs.getObject("owner_entity_id", UUID.class);

        Map<String, Object> attributes = null;

        if (scope.policyClass() == PolicyClass.OWN && owner != null && owner.equals(scope.entityId())) {
            String json = rs.getString("attributes_json");
            try {
                attributes = mapper.readValue(json == null ? "{}" : json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new SQLException("Cannot read SKU attributes", e);
            }
        }

        return new SkuView(
                rs.getObject("sku_id", UUID.class),
                rs.getString("sku_code"),
                owner,
                rs.getString("status"),
                rs.getString("short_name_en"),
                rs.getString("short_name_si"),
                rs.getString("short_name_ta"),
                rs.getString("description_en"),
                rs.getString("description_si"),
                rs.getString("description_ta"),
                rs.getString("base_uom_code"),
                rs.getBoolean("sold_by_weight"),
                rs.getBoolean("batch_tracked"),
                rs.getBoolean("expiry_tracked"),
                rs.getBoolean("has_printed_mrp"),
                rs.getObject("expiry_warning_days", Short.class),
                rs.getObject("tax_category_id", UUID.class),
                rs.getString("multi_mrp_policy"),
                rs.getString("origin_kind"),
                attributes);
    }
}
