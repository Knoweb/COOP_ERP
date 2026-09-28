package lk.coopfed.knoweb.m3pricing.internal.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of pricing.discount_rule, and the JSON form of a predicate and a benefit. The JSON keys
 * are snake_case, as the till reads them from its snapshot (sku_id, uom_code, min_qty,
 * days_to_expiry, bill_total_from; kind, value). Row-level security limits every read to what the
 * caller's scope may see.
 */
@Component
public class RuleStore {

    static final String DRAFT = "DRAFT";
    static final String ACTIVE = "ACTIVE";
    static final String WITHDRAWN = "WITHDRAWN";

    private static final String COLUMNS = "rule_id, owner_entity_id, name, kind, predicate::text as predicate,"
            + " benefit::text as benefit, priority, valid_from, valid_to, status, created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    RuleStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<RuleView> find(UUID ruleId) {
        return jdbc
                .query("select " + COLUMNS + " from pricing.discount_rule where rule_id = ?", this::rule, ruleId)
                .stream()
                .findFirst();
    }

    public List<RuleView> list(String status, String kind) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from pricing.discount_rule where true");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" and status = ?");
            args.add(status);
        }
        if (kind != null) {
            sql.append(" and kind = ?");
            args.add(kind);
        }
        sql.append(" order by valid_from desc, name, rule_id");
        return jdbc.query(sql.toString(), this::rule, args.toArray());
    }

    /** The ACTIVE rules of the caller's entity whose validity has not ended before the date. */
    public List<RuleView> activeOnOrAfter(LocalDate date) {
        return jdbc.query(
                "select " + COLUMNS + " from pricing.discount_rule"
                        + " where status = 'ACTIVE' and (valid_to is null or valid_to >= ?)"
                        + " order by rule_id",
                this::rule,
                Date.valueOf(date));
    }

    String predicateJson(RulePredicate p) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "sku_id", p.skuId() == null ? null : p.skuId().toString());
        putIfPresent(map, "uom_code", p.uomCode());
        putIfPresent(map, "min_qty", plain(p.minQty()));
        putIfPresent(map, "days_to_expiry", p.daysToExpiry());
        putIfPresent(map, "bill_total_from", plain(p.billTotalFrom()));
        return write(map);
    }

    String benefitJson(RuleBenefit b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", b.kind());
        map.put("value", plain(b.value()));
        return write(map);
    }

    private RuleView rule(ResultSet rs, int row) throws SQLException {
        Date validTo = rs.getDate("valid_to");
        JsonNode p = read(rs.getString("predicate"));
        JsonNode b = read(rs.getString("benefit"));
        return new RuleView(
                rs.getObject("rule_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("name"),
                rs.getString("kind"),
                new RulePredicate(
                        p.hasNonNull("sku_id") ? UUID.fromString(p.get("sku_id").asText()) : null,
                        p.hasNonNull("uom_code") ? p.get("uom_code").asText() : null,
                        decimal(p, "min_qty"),
                        p.hasNonNull("days_to_expiry") ? p.get("days_to_expiry").asInt() : null,
                        decimal(p, "bill_total_from")),
                new RuleBenefit(b.path("kind").asText(null), decimal(b, "value")),
                rs.getInt("priority"),
                rs.getDate("valid_from").toLocalDate(),
                validTo == null ? null : validTo.toLocalDate(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Decimals are kept as text in the JSON, as in the snapshot: nothing rounds them on the way. */
    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String write(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A rule's JSON could not be written", e);
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A rule's JSON could not be read", e);
        }
    }
}
