package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The named projection queries a report definition may name as its source (28A section 7,
 * DefinitionValidator: "PROJECTION_QUERY -> named query over reporting.* only (allow-list of
 * tables)"). Each reads the projections in the caller's scope and fills the keys it declares in
 * {@link #KEYS}; the validator refuses a definition whose source is not here or whose column is
 * not among the source's keys. Values are plain text: names through {@link Names}, dates ISO,
 * amounts as stored.
 */
@Component
class ReportSources {

    /** The keys each source fills, for the validator. */
    static final Map<String, Set<String>> KEYS = Map.of(
            "stock-position", Set.of("entity", "location", "skuCode", "skuName", "qty", "value"),
            "trade-received", Set.of("date", "seller", "buyer", "skuCode", "skuName", "qty", "value"),
            "invoices-issued", Set.of("date", "number", "seller", "buyer", "net", "tax", "gross"),
            "statement-of-account",
                    Set.of(
                            "date",
                            "number",
                            "seller",
                            "buyer",
                            "dueDate",
                            "gross",
                            "credited",
                            "paid",
                            "outstanding",
                            "daysOverdue"),
            "receivables-ageing",
                    Set.of(
                            "seller",
                            "buyer",
                            "invoices",
                            "notDue",
                            "d1to30",
                            "d31to60",
                            "d61to90",
                            "d90plus",
                            "outstanding"),
            "fill-rate",
                    Set.of(
                            "seller",
                            "buyer",
                            "grns",
                            "expected",
                            "received",
                            "fillRate",
                            "withEta",
                            "onTime",
                            "onTimeRate"),
            "shop-sales-by-shop", Set.of("date", "location", "receipts", "net", "tax", "gross"),
            "shop-sales-by-item", Set.of("skuCode", "skuName", "qty", "value"));

    /** The sources that read a period: their definitions must take one. */
    static final Set<String> PERIOD = Set.of(
            "trade-received",
            "invoices-issued",
            "statement-of-account",
            "fill-rate",
            "shop-sales-by-shop",
            "shop-sales-by-item");

    /** The projection whose freshness a source's answer carries. */
    static final Map<String, String> PROJECTION = Map.of(
            "stock-position", "stock_position",
            "shop-sales-by-shop", "shop_sales",
            "shop-sales-by-item", "shop_sales");

    private final JdbcTemplate jdbc;

    ReportSources(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String projection(String source) {
        return PROJECTION.getOrDefault(source, "trade");
    }

    /**
     * The rows of a source.
     *
     * @param cost  whether the caller sees cost (the stock value)
     * @param today the business date today, for what is due and overdue
     */
    List<Map<String, String>> rows(
            String source, ReportParameters parameters, Names names, boolean cost, LocalDate today, int maxRows) {
        // One row more than the cap is read, in SQL, so that "too many" is known without loading
        // them all (wave 2, M8-09); the names are resolved only for rows that are answered.
        String cap = " limit " + (maxRows + 1);
        List<Map<String, String>> rows =
                switch (source) {
                    case "stock-position" -> stock(parameters, names, cost, cap);
                    case "trade-received" -> trade(parameters, names, cap);
                    case "invoices-issued" -> invoices(parameters, names, cap);
                    case "statement-of-account" -> statement(parameters, names, today, cap);
                    case "receivables-ageing" -> ageing(names, today, cap);
                    case "fill-rate" -> fillRate(parameters, names, cap);
                    case "shop-sales-by-shop" -> shopSalesByShop(parameters, names, cap);
                    case "shop-sales-by-item" -> shopSalesByItem(parameters, names, cap);
                    default -> throw new ProblemException("m8.report.unknown", Map.of("reportId", source));
                };
        if (rows.size() > maxRows) {
            throw new ProblemException("m8.report.too_many_rows", Map.of("rows", maxRows));
        }
        return rows;
    }

    private List<Map<String, String>> stock(ReportParameters parameters, Names names, boolean cost, String cap) {
        return jdbc.query(
                """
                select owner_entity_id, location_id, canonical_sku_id,
                       sum(qty_on_hand) as qty, sum(qty_on_hand * coalesce(unit_cost, 0)) as value
                  from reporting.stock_position
                 where (?::uuid is null or location_id = ?)
                 group by owner_entity_id, location_id, canonical_sku_id
                having sum(qty_on_hand) <> 0
                 order by owner_entity_id, location_id, canonical_sku_id
                """
                        + cap,
                (rs, n) -> {
                    UUID sku = rs.getObject("canonical_sku_id", UUID.class);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("entity", names.entity(rs.getObject("owner_entity_id", UUID.class)));
                    row.put("location", names.location(rs.getObject("location_id", UUID.class)));
                    row.put("skuCode", names.skuCode(sku));
                    row.put("skuName", names.skuName(sku));
                    row.put("qty", plain(rs.getBigDecimal("qty")));
                    if (cost) {
                        row.put("value", plain(money(rs.getBigDecimal("value"))));
                    }
                    return row;
                },
                parameters.locationId(),
                parameters.locationId());
    }

    private List<Map<String, String>> trade(ReportParameters parameters, Names names, String cap) {
        return jdbc.query(
                """
                select business_date, seller_entity_id, buyer_entity_id, sku_id, sum(qty) as qty, sum(value) as value
                  from reporting.trade_line_fact
                 where measure = 'RECEIVED' and business_date between ? and ?
                 group by business_date, seller_entity_id, buyer_entity_id, sku_id
                 order by business_date, seller_entity_id, buyer_entity_id, sku_id
                """
                        + cap,
                (rs, n) -> {
                    UUID sku = rs.getObject("sku_id", UUID.class);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("date", date(rs, "business_date"));
                    row.put("seller", names.entity(rs.getObject("seller_entity_id", UUID.class)));
                    row.put("buyer", names.entity(rs.getObject("buyer_entity_id", UUID.class)));
                    row.put("skuCode", names.skuCode(sku));
                    row.put("skuName", names.skuName(sku));
                    row.put("qty", plain(rs.getBigDecimal("qty")));
                    row.put("value", plain(rs.getBigDecimal("value")));
                    return row;
                },
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()));
    }

    private List<Map<String, String>> invoices(ReportParameters parameters, Names names, String cap) {
        // One row per invoice: the Federation view reads the seller's row only once, and a
        // buyer reads the seller's row through party_read.
        return jdbc.query(
                """
                select distinct on (business_date, doc_number, document_id)
                       business_date, doc_number, seller_entity_id, buyer_entity_id, net, tax, gross
                  from reporting.trade_document_event
                 where doc_type = 'INVOICE' and event_kind = 'ISSUED' and business_date between ? and ?
                 order by business_date, doc_number, document_id, occurred_at desc, event_id desc
                """
                        + cap,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("date", date(rs, "business_date"));
                    row.put("number", rs.getString("doc_number"));
                    row.put("seller", names.entity(rs.getObject("seller_entity_id", UUID.class)));
                    row.put("buyer", names.entity(rs.getObject("buyer_entity_id", UUID.class)));
                    row.put("net", plain(rs.getBigDecimal("net")));
                    row.put("tax", plain(rs.getBigDecimal("tax")));
                    row.put("gross", plain(rs.getBigDecimal("gross")));
                    return row;
                },
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()));
    }

    private List<Map<String, String>> statement(ReportParameters parameters, Names names, LocalDate today, String cap) {
        return jdbc.query(
                "select * from " + TradeSql.INVOICES + " inv where business_date between ? and ?"
                        + " order by business_date, doc_number, document_id" + cap,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("date", date(rs, "business_date"));
                    row.put("number", rs.getString("doc_number"));
                    row.put("seller", names.entity(rs.getObject("seller_entity_id", UUID.class)));
                    row.put("buyer", names.entity(rs.getObject("buyer_entity_id", UUID.class)));
                    row.put("dueDate", date(rs, "due_date"));
                    row.put("gross", plain(rs.getBigDecimal("gross")));
                    row.put("credited", plain(rs.getBigDecimal("credited")));
                    row.put("paid", plain(rs.getBigDecimal("paid")));
                    BigDecimal outstanding = rs.getBigDecimal("outstanding");
                    row.put("outstanding", plain(outstanding));
                    Date due = rs.getDate("due_date");
                    long overdue = due == null || outstanding.signum() == 0
                            ? 0
                            : Math.max(0, ChronoUnit.DAYS.between(due.toLocalDate(), today));
                    row.put("daysOverdue", String.valueOf(overdue));
                    return row;
                },
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()));
    }

    private List<Map<String, String>> ageing(Names names, LocalDate today, String cap) {
        // Days past the due date, today: not yet due, 1-30, 31-60, 61-90, over 90. An invoice
        // with no due date counts as due on its tax point.
        return jdbc.query(
                """
                select seller_entity_id, buyer_entity_id, count(*) as invoices,
                       sum(case when age <= 0 then outstanding else 0 end) as not_due,
                       sum(case when age between 1 and 30 then outstanding else 0 end) as d1to30,
                       sum(case when age between 31 and 60 then outstanding else 0 end) as d31to60,
                       sum(case when age between 61 and 90 then outstanding else 0 end) as d61to90,
                       sum(case when age > 90 then outstanding else 0 end) as d90plus,
                       sum(outstanding) as outstanding
                  from (select inv.*, (?::date - coalesce(inv.due_date, inv.business_date)) as age
                          from %s inv where inv.outstanding > 0) open
                 group by seller_entity_id, buyer_entity_id
                 order by seller_entity_id, buyer_entity_id
                """
                                .formatted(TradeSql.INVOICES)
                        + cap,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("seller", names.entity(rs.getObject("seller_entity_id", UUID.class)));
                    row.put("buyer", names.entity(rs.getObject("buyer_entity_id", UUID.class)));
                    row.put("invoices", String.valueOf(rs.getLong("invoices")));
                    row.put("notDue", plain(rs.getBigDecimal("not_due")));
                    row.put("d1to30", plain(rs.getBigDecimal("d1to30")));
                    row.put("d31to60", plain(rs.getBigDecimal("d31to60")));
                    row.put("d61to90", plain(rs.getBigDecimal("d61to90")));
                    row.put("d90plus", plain(rs.getBigDecimal("d90plus")));
                    row.put("outstanding", plain(rs.getBigDecimal("outstanding")));
                    return row;
                },
                Date.valueOf(today));
    }

    private List<Map<String, String>> fillRate(ReportParameters parameters, Names names, String cap) {
        // The fill rate: what was received of what the delivery notes expected, a line never
        // counting more than it expected (an over-delivery does not make up for a short one).
        // On time: a GRN received on or before the latest date committed for its orders.
        return jdbc.query(
                """
                select l.seller_entity_id, l.buyer_entity_id,
                       count(distinct l.document_id) as grns,
                       sum(l.expected_qty) as expected,
                       sum(l.qty) as received,
                       sum(least(l.qty, l.expected_qty)) as filled,
                       (select count(*) from %1$s e
                         where e.seller_entity_id = l.seller_entity_id and e.buyer_entity_id = l.buyer_entity_id
                           and e.business_date between ? and ? and e.committed_eta is not null) as with_eta,
                       (select count(*) from %1$s e
                         where e.seller_entity_id = l.seller_entity_id and e.buyer_entity_id = l.buyer_entity_id
                           and e.business_date between ? and ? and e.business_date <= e.committed_eta) as on_time
                  from reporting.trade_line_fact l
                 where l.measure = 'RECEIVED' and l.expected_qty is not null and l.expected_qty > 0
                   and l.business_date between ? and ?
                 group by l.seller_entity_id, l.buyer_entity_id
                 order by l.seller_entity_id, l.buyer_entity_id
                """
                                .formatted(TradeSql.GRN_ETA)
                        + cap,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("seller", names.entity(rs.getObject("seller_entity_id", UUID.class)));
                    row.put("buyer", names.entity(rs.getObject("buyer_entity_id", UUID.class)));
                    row.put("grns", String.valueOf(rs.getLong("grns")));
                    row.put("expected", plain(rs.getBigDecimal("expected")));
                    row.put("received", plain(rs.getBigDecimal("received")));
                    row.put("fillRate", plain(percent(rs.getBigDecimal("filled"), rs.getBigDecimal("expected"))));
                    long withEta = rs.getLong("with_eta");
                    long onTime = rs.getLong("on_time");
                    row.put("withEta", String.valueOf(withEta));
                    row.put("onTime", String.valueOf(onTime));
                    row.put("onTimeRate", plain(percent(BigDecimal.valueOf(onTime), BigDecimal.valueOf(withEta))));
                    return row;
                },
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()),
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()),
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()));
    }

    private List<Map<String, String>> shopSalesByShop(ReportParameters parameters, Names names, String cap) {
        return jdbc.query(
                """
                select business_date, location_id, count(*) as receipts,
                       sum(net) as net, sum(tax) as tax, sum(gross) as gross
                  from reporting.shop_sale_fact
                 where business_date between ? and ? and (?::uuid is null or location_id = ?)
                 group by business_date, location_id
                 order by business_date, location_id
                """
                        + cap,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("date", date(rs, "business_date"));
                    row.put("location", names.location(rs.getObject("location_id", UUID.class)));
                    row.put("receipts", String.valueOf(rs.getLong("receipts")));
                    row.put("net", plain(rs.getBigDecimal("net")));
                    row.put("tax", plain(rs.getBigDecimal("tax")));
                    row.put("gross", plain(rs.getBigDecimal("gross")));
                    return row;
                },
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()),
                parameters.locationId(),
                parameters.locationId());
    }

    private List<Map<String, String>> shopSalesByItem(ReportParameters parameters, Names names, String cap) {
        return jdbc.query(
                """
                select sku_id, sum(qty) as qty, sum(line_total) as value
                  from reporting.shop_sale_line_fact
                 where business_date between ? and ? and (?::uuid is null or location_id = ?)
                 group by sku_id
                 order by sum(line_total) desc nulls last, sku_id
                """
                        + cap,
                (rs, n) -> {
                    UUID sku = rs.getObject("sku_id", UUID.class);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("skuCode", names.skuCode(sku));
                    row.put("skuName", names.skuName(sku));
                    row.put("qty", plain(rs.getBigDecimal("qty")));
                    row.put("value", plain(rs.getBigDecimal("value")));
                    return row;
                },
                Date.valueOf(parameters.from()),
                Date.valueOf(parameters.to()),
                parameters.locationId(),
                parameters.locationId());
    }

    /** part / whole x 100, to one decimal; none when the whole is zero. */
    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? "" : value.toLocalDate().toString();
    }
}
