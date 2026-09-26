package lk.coopfed.knoweb.m2catalogue.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
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
