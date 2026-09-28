package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.query.Dashboard.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The named queries a dashboard tile may name as its source (28A section 7, dashboard/TileService:
 * "tile.source is a named projection query"), each over reporting.* in the caller's scope.
 *
 * <p>"Mine" is the caller's entity: a seller's sales and receivables, a buyer's purchases and
 * payables. The Federation view has no side of its own and reads every party's figures. A source
 * answers null when the caller has nothing of the kind at all (a society that sells nothing has
 * no sales tile), and the tile is left out.
 *
 * <p>Periods: a trend is the eight weeks ending today (the oldest week starts 55 days ago), and a
 * trend tile's value is its last week, today and the six days before. The rates (fill rate,
 * on time) cover the same eight weeks. Every date is a business date in
 * {@code coop-erp.business-timezone}, read once per dashboard, so a dashboard is the same day
 * throughout.
 */
@Component
class TileSources {

    /** The weeks of a trend. */
    static final int WEEKS = 8;

    static final Set<String> NAMES = Set.of(
            "sales",
            "purchases",
            "shop-sales",
            "receivables",
            "overdue-receivables",
            "payables",
            "overdue-payables",
            "exposure",
            "fill-rate-out",
            "on-time-out",
            "fill-rate-in",
            "on-time-in",
            "exceptions",
            "open-orders",
            "deliveries-in-transit",
            "grns-today",
            "stock-value");

    /** The sources that can answer a trend. */
    static final Set<String> TREND = Set.of("sales", "purchases", "shop-sales");

    /** A tile's figure, and its weeks for a trend tile. */
    record Value(BigDecimal value, List<Point> trend) {}

    /** What a source needs to know about the caller and the day. */
    record Context(ScopeContext scope, LocalDate today, int exceptions) {

        UUID me() {
            return scope.entityId();
        }

        boolean everyone() {
            return scope.policyClass() == PolicyClass.FEDERATION_VIEW || scope.entityId() == null;
        }
    }

    private final JdbcTemplate jdbc;

    TileSources(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Value value(String source, Context context) {
        LocalDate today = context.today();
        return switch (source) {
            case "sales" ->
                trend(
                        """
                    select business_date as d, net as v from reporting.trade_document_event
                     where doc_type = 'INVOICE' and event_kind = 'ISSUED' and (? or seller_entity_id = ?)
                    """,
                        context);
            case "purchases" ->
                trend(
                        """
                    select business_date as d, net as v from reporting.trade_document_event
                     where doc_type = 'INVOICE' and event_kind = 'ISSUED' and (? or buyer_entity_id = ?)
                    """,
                        context);
            case "shop-sales" ->
                trend(
                        "select business_date as d, gross as v from reporting.shop_sale_fact where (?::boolean or ?::uuid is not null)",
                        context);
            case "receivables" -> outstanding("seller_entity_id", false, context);
            case "overdue-receivables" -> outstanding("seller_entity_id", true, context);
            case "payables" -> outstanding("buyer_entity_id", false, context);
            case "overdue-payables" -> outstanding("buyer_entity_id", true, context);
            case "exposure" ->
                percent(jdbc.queryForObject(
                        "select max(case when e.credit_limit > 0 then e.exposure * 100 / e.credit_limit end)"
                                + " from " + TradeSql.EXPOSURE + " e"
                                + " where (? or e.seller_entity_id = ? or e.buyer_entity_id = ?)",
                        BigDecimal.class,
                        context.everyone(),
                        context.me(),
                        context.me()));
            case "fill-rate-out" -> fillRate("seller_entity_id", context);
            case "fill-rate-in" -> fillRate("buyer_entity_id", context);
            case "on-time-out" -> onTime("seller_entity_id", context);
            case "on-time-in" -> onTime("buyer_entity_id", context);
            case "exceptions" -> new Value(BigDecimal.valueOf(context.exceptions()), List.of());
            case "open-orders" ->
                count(
                        """
                    select count(distinct s.document_id) from reporting.trade_document_event s
                     where s.doc_type = 'ORDER' and s.event_kind = 'SUBMITTED'
                       and not exists (select 1 from reporting.trade_document_event x
                                        where x.document_id = s.document_id
                                          and x.event_kind in ('ACCEPTED', 'REJECTED', 'CANCELLED'))
                    """);
            case "deliveries-in-transit" ->
                count(
                        """
                    select count(distinct d.document_id) from reporting.trade_document_event d
                     where d.doc_type = 'DELIVERY_NOTE' and d.event_kind = 'DISPATCHED'
                       and not exists (select 1 from reporting.trade_document_event g
                                        where g.doc_type = 'GRN' and g.event_kind = 'CONFIRMED'
                                          and g.reference_document_id = d.document_id)
                    """);
            case "grns-today" ->
                count(
                        "select count(distinct document_id) from reporting.trade_document_event"
                                + " where doc_type = 'GRN' and event_kind = 'CONFIRMED' and business_date = ?",
                        Date.valueOf(today));
            case "stock-value" ->
                new Value(
                        ReportSources.money(jdbc.queryForObject(
                                "select coalesce(sum(qty_on_hand * coalesce(unit_cost, 0)), 0) from reporting.stock_position",
                                BigDecimal.class)),
                        List.of());
            default -> throw new IllegalStateException("No tile source " + source);
        };
    }

    /**
     * A trend over rows of (d date, v amount) the base query selects, filtered by "(? or column =
     * ?)" with the caller: the eight weeks ending today, and the last as the value.
     */
    private Value trend(String base, Context context) {
        Boolean any =
                jdbc.queryForObject("select exists (" + base + ")", Boolean.class, context.everyone(), context.me());
        if (!Boolean.TRUE.equals(any)) {
            return null;
        }
        Map<Integer, BigDecimal> weeks = new HashMap<>();
        jdbc.query(
                "select (?::date - b.d) / 7 as week, coalesce(sum(b.v), 0) as v from (" + base + ") b"
                        + " where b.d between ?::date - " + (WEEKS * 7 - 1) + " and ?::date group by 1",
                rs -> {
                    weeks.put(rs.getInt("week"), rs.getBigDecimal("v"));
                },
                Date.valueOf(context.today()),
                context.everyone(),
                context.me(),
                Date.valueOf(context.today()),
                Date.valueOf(context.today()));
        List<Point> points = new ArrayList<>();
        for (int week = WEEKS - 1; week >= 0; week--) {
            LocalDate from = context.today().minusDays(week * 7L + 6);
            points.add(new Point(from, ReportSources.money(weeks.getOrDefault(week, BigDecimal.ZERO))));
        }
        return new Value(points.get(points.size() - 1).value(), points);
    }

    /** What is still due on the caller's invoices (as seller or buyer); only what is past its due date. */
    private Value outstanding(String side, boolean overdue, Context context) {
        String mine = "(? or inv." + side + " = ?)";
        Boolean any = jdbc.queryForObject(
                "select exists (select 1 from " + TradeSql.INVOICES + " inv where " + mine + ")",
                Boolean.class,
                context.everyone(),
                context.me());
        if (!Boolean.TRUE.equals(any)) {
            return null;
        }
        BigDecimal sum = jdbc.queryForObject(
                "select coalesce(sum(inv.outstanding), 0) from " + TradeSql.INVOICES + " inv where " + mine
                        + (overdue ? " and coalesce(inv.due_date, inv.business_date) < ?" : " and ?::date is not null"),
                BigDecimal.class,
                context.everyone(),
                context.me(),
                Date.valueOf(context.today()));
        return new Value(ReportSources.money(sum), List.of());
    }

    private Value fillRate(String side, Context context) {
        Map<String, Object> row = jdbc.queryForMap(
                "select sum(least(qty, expected_qty)) as filled, sum(expected_qty) as expected"
                        + " from reporting.trade_line_fact"
                        + " where measure = 'RECEIVED' and expected_qty > 0 and (? or " + side + " = ?)"
                        + " and business_date between ?::date - " + (WEEKS * 7 - 1) + " and ?::date",
                context.everyone(),
                context.me(),
                Date.valueOf(context.today()),
                Date.valueOf(context.today()));
        BigDecimal rate = ReportSources.percent((BigDecimal) row.get("filled"), (BigDecimal) row.get("expected"));
        return rate == null ? null : new Value(rate, List.of());
    }

    private Value onTime(String side, Context context) {
        Map<String, Object> row = jdbc.queryForMap(
                "select count(*) filter (where e.business_date <= e.committed_eta) as on_time, count(*) as total"
                        + " from " + TradeSql.GRN_ETA + " e"
                        + " where e.committed_eta is not null and (? or e." + side + " = ?)"
                        + " and e.business_date between ?::date - " + (WEEKS * 7 - 1) + " and ?::date",
                context.everyone(),
                context.me(),
                Date.valueOf(context.today()),
                Date.valueOf(context.today()));
        long total = ((Number) row.get("total")).longValue();
        if (total == 0) {
            return null;
        }
        return new Value(
                ReportSources.percent(
                        BigDecimal.valueOf(((Number) row.get("on_time")).longValue()), BigDecimal.valueOf(total)),
                List.of());
    }

    private Value count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return new Value(BigDecimal.valueOf(count == null ? 0 : count), List.of());
    }

    private static Value percent(BigDecimal value) {
        return value == null ? null : new Value(value.setScale(1, java.math.RoundingMode.HALF_UP), List.of());
    }
}
