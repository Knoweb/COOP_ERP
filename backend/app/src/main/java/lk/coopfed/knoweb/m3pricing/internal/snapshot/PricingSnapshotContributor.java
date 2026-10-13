package lk.coopfed.knoweb.m3pricing.internal.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import lk.coopfed.knoweb.m3pricing.internal.list.Federation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The pricing, rules, control prices, and MRP policies in a shop's till snapshot (23A section 7.2;
 * doc 32 section 5.1).
 *
 * <p>Snapshot tables:
 * <ul>
 *   <li>{@code price_list_line} (row id = line id): lines of the owner MPCS's PUBLISHED (and
 *       SUPERSEDED-but-still-in-force) retail price list. Decimals as text; tier_from_qty as text.
 *   <li>{@code discount_rule} (row id = rule id): ACTIVE rules of the owner MPCS whose validity has
 *       not ended before today; predicate and benefit as nested JSON objects.
 *   <li>{@code control_price} (row id = control price id): Federation ceilings in force today or
 *       scheduled for the future; ceiling_price as text.
 *   <li>{@code mrp_policy} (row id = policy id): effective policies for the assortment's SKUs: the
 *       shop's owning entity row, falling back to the Federation's default row when no entity override exists.
 * </ul>
 */
@Component
public class PricingSnapshotContributor implements SnapshotContributor {

    public static final String PRICE_LIST_LINE = "price_list_line";
    public static final String DISCOUNT_RULE = "discount_rule";
    public static final String CONTROL_PRICE = "control_price";
    public static final String MRP_POLICY = "mrp_policy";

    private static final String DEFAULT_POLICY_KEY = "pricing.default_mrp_policy";
    private static final String GAP_AMOUNT_KEY = "pricing.picker_gap_amount";
    private static final String GAP_PERCENT_KEY = "pricing.picker_gap_percent";
    private static final String PICKER = "PICKER";
    private static final String AUTO_LOWEST = "AUTO_LOWEST";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Federation federation;
    private final ConfigRegistry config;
    private final Clock clock;

    PricingSnapshotContributor(
            JdbcTemplate jdbc, ObjectMapper json, Federation federation, ConfigRegistry config, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.federation = federation;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Set<String> tables() {
        return Set.of(PRICE_LIST_LINE, DISCOUNT_RULE, CONTROL_PRICE, MRP_POLICY);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return Map.of();
        }
        return read(table, shop, rowIds, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        return read(table, shop, null, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** Every row of the table in the shop's snapshot as of the given business date. */
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop, LocalDate asOf) {
        return read(table, shop, null, asOf);
    }

    private Map<UUID, Map<String, Object>> read(String table, Shop shop, Collection<UUID> ids, LocalDate asOf) {
        return switch (table) {
            case PRICE_LIST_LINE -> priceListLines(shop, ids, asOf);
            case DISCOUNT_RULE -> discountRules(shop, ids, asOf);
            case CONTROL_PRICE -> controlPrices(ids, asOf);
            case MRP_POLICY -> mrpPolicies(shop, ids);
            default -> throw new IllegalArgumentException("Not an M3 snapshot table: " + table);
        };
    }

    // ---- price_list_line ----------------------------------------------------------------------

    private Map<UUID, Map<String, Object>> priceListLines(Shop shop, Collection<UUID> ids, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("ownerEntityId", shop.ownerEntityId());
        params.addValue("ids", ids);
        params.addValue("today", Date.valueOf(today));
        String onlyIds = ids == null ? "" : " and pll.line_id in (:ids)";

        // Active lists: the newest list in force on today, plus any scheduled future PUBLISHED list
        Map<UUID, Map<String, Object>> lines = new LinkedHashMap<>();
        jdbc.query(
                """
                with active_lists as (
                    (select price_list_id from pricing.price_list
                      where kind = 'RETAIL' and owner_entity_id = :ownerEntityId
                        and status in ('PUBLISHED', 'SUPERSEDED') and apply_from <= :today
                      order by apply_from desc, version desc limit 1)
                    union all
                    (select price_list_id from pricing.price_list
                      where kind = 'RETAIL' and owner_entity_id = :ownerEntityId
                        and status = 'PUBLISHED' and apply_from > :today)
                )
                select pll.line_id, pll.price_list_id, pll.sku_id, pll.uom_code, pll.tier_from_qty, pll.price,
                       pll.effective_from, pll.effective_to
                  from pricing.price_list_line pll
                  join active_lists al on al.price_list_id = pll.price_list_id
                 where (pll.effective_to is null or pll.effective_to >= :today)"""
                        + onlyIds
                        + " order by pll.line_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sku_id", rs.getString("sku_id"));
                    row.put("uom_code", rs.getString("uom_code"));
                    row.put("price", text(rs.getBigDecimal("price")));
                    row.put("unit_price", text(rs.getBigDecimal("price")));
                    row.put("tier_from_qty", text(rs.getBigDecimal("tier_from_qty")));
                    row.put("effective_from", text(rs.getDate("effective_from")));
                    row.put("effective_to", text(rs.getDate("effective_to")));
                    row.put("price_list_id", rs.getString("price_list_id"));
                    lines.put(rs.getObject("line_id", UUID.class), row);
                });
        return lines;
    }

    // ---- discount_rule ------------------------------------------------------------------------

    private Map<UUID, Map<String, Object>> discountRules(Shop shop, Collection<UUID> ids, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("ownerEntityId", shop.ownerEntityId());
        params.addValue("ids", ids);
        params.addValue("today", Date.valueOf(today));
        String onlyIds = ids == null ? "" : " and r.rule_id in (:ids)";

        Map<UUID, Map<String, Object>> rules = new LinkedHashMap<>();
        jdbc.query(
                """
                select r.rule_id, r.name, r.kind, r.predicate::text as predicate, r.benefit::text as benefit,
                       r.priority, r.valid_from, r.valid_to
                  from pricing.discount_rule r
                 where r.owner_entity_id = :ownerEntityId
                   and r.status = 'ACTIVE'
                   and (r.valid_to is null or r.valid_to >= :today)"""
                        + onlyIds
                        + " order by r.rule_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("name"));
                    row.put("kind", rs.getString("kind"));
                    row.put("predicate", parseJson(rs.getString("predicate")));
                    row.put("benefit", parseJson(rs.getString("benefit")));
                    row.put("priority", rs.getInt("priority"));
                    row.put("valid_from", text(rs.getDate("valid_from")));
                    row.put("valid_to", text(rs.getDate("valid_to")));
                    rules.put(rs.getObject("rule_id", UUID.class), row);
                });
        return rules;
    }

    // ---- control_price ------------------------------------------------------------------------

    private Map<UUID, Map<String, Object>> controlPrices(Collection<UUID> ids, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("ids", ids);
        params.addValue("today", Date.valueOf(today));
        String onlyIds = ids == null ? "" : " and cp.control_price_id in (:ids)";

        Map<UUID, Map<String, Object>> ceilings = new LinkedHashMap<>();
        jdbc.query(
                """
                select cp.control_price_id, cp.sku_id, cp.ceiling_price, cp.ceiling_uom_code,
                       cp.effective_from, cp.effective_to, cp.gazette_reference
                  from pricing.control_price cp
                 where (cp.effective_to is null or cp.effective_to >= :today)"""
                        + onlyIds
                        + " order by cp.control_price_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sku_id", rs.getString("sku_id"));
                    row.put("ceiling_price", text(rs.getBigDecimal("ceiling_price")));
                    row.put("ceiling_uom_code", rs.getString("ceiling_uom_code"));
                    row.put("effective_from", text(rs.getDate("effective_from")));
                    row.put("effective_to", text(rs.getDate("effective_to")));
                    row.put("gazette_reference", rs.getString("gazette_reference"));
                    ceilings.put(rs.getObject("control_price_id", UUID.class), row);
                });
        return ceilings;
    }

    // ---- mrp_policy ---------------------------------------------------------------------------

    private Map<UUID, Map<String, Object>> mrpPolicies(Shop shop, Collection<UUID> ids) {
        UUID fedId = federation.entityId().orElse(new UUID(0, 0));
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("ownerEntityId", shop.ownerEntityId());
        params.addValue("fedEntityId", fedId);
        params.addValue("ids", ids);
        String onlyIds = ids == null ? "" : " and p.policy_id in (:ids)";

        BigDecimal defaultGapAmount =
                config.get(GAP_AMOUNT_KEY, null).map(BigDecimal::new).orElse(null);
        BigDecimal defaultGapPercent =
                config.get(GAP_PERCENT_KEY, null).map(BigDecimal::new).orElse(null);

        Map<UUID, Map<String, Object>> policies = new LinkedHashMap<>();
        jdbc.query(
                """
                select p.policy_id, p.sku_id, p.policy, p.picker_gap_amount, p.picker_gap_percent, p.owner_entity_id
                  from pricing.mrp_policy p
                 where (p.owner_entity_id = :ownerEntityId
                        or (p.owner_entity_id = :fedEntityId
                            and not exists (
                                select 1 from pricing.mrp_policy o
                                 where o.owner_entity_id = :ownerEntityId
                                   and o.sku_id = p.sku_id
                            )))"""
                        + onlyIds
                        + " order by p.policy_id",
                params,
                rs -> {
                    String policy = rs.getString("policy");
                    BigDecimal gapAmount = rs.getBigDecimal("picker_gap_amount");
                    BigDecimal gapPercent = rs.getBigDecimal("picker_gap_percent");
                    if (PICKER.equals(policy) && gapAmount == null && gapPercent == null) {
                        gapAmount = defaultGapAmount;
                        gapPercent = defaultGapPercent;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sku_id", rs.getString("sku_id"));
                    row.put("policy", policy);
                    row.put("picker_gap_amount", text(gapAmount));
                    row.put("picker_gap_percent", text(gapPercent));
                    row.put("owner_entity_id", rs.getString("owner_entity_id"));
                    policies.put(rs.getObject("policy_id", UUID.class), row);
                });
        return policies;
    }

    // ---- helpers ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse discount rule JSON: " + text, e);
        }
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String text(Date date) {
        return date == null ? null : date.toLocalDate().toString();
    }
}
