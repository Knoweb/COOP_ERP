package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m8reporting.internal.report.ReportCatalogue.Definition;
import lk.coopfed.knoweb.m8reporting.query.Dashboard;
import lk.coopfed.knoweb.m8reporting.query.ReportDefinitionView;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportRunView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of M8 (28A section 7): the three reports of the demo, the dashboard and a report
 * run. Read-only transactions in the caller's scope: row-level security decides which rows of
 * the projections a report is made of (the owner's, the counterparty's through party_read, all
 * of them for the Federation view), and no query names an owner.
 */
@Service
@Transactional(readOnly = true)
class ReportQueriesImpl implements ReportingQueries {

    static final String TILE_OPEN_ORDERS = "open-orders";
    static final String TILE_IN_TRANSIT = "deliveries-in-transit";
    static final String TILE_GRNS_TODAY = "grns-today";
    static final String TILE_STOCK_VALUE = "stock-value";

    private final JdbcTemplate jdbc;
    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final Clock clock;
    private final ZoneId zone;

    ReportQueriesImpl(
            JdbcTemplate jdbc,
            PartyQueries party,
            CatalogueQueries catalogue,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.jdbc = jdbc;
        this.party = party;
        this.catalogue = catalogue;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public List<ReportDefinitionView> definitions(ScopeContext scope) {
        return ReportCatalogue.ALL.stream().map(Definition::view).toList();
    }

    @Override
    public ReportTable report(String reportId, ReportParameters parameters, ScopeContext scope) {
        Definition definition = definition(reportId);
        checkPeriod(definition, parameters);
        Names names = new Names(party, catalogue, scope);
        List<Column> columns = new ArrayList<>(definition.columns());
        List<Map<String, String>> rows;
        String projection;
        switch (definition.reportId()) {
            case ReportCatalogue.STOCK_POSITION -> {
                projection = "stock_position";
                boolean cost = seesCost(scope);
                if (!cost) {
                    columns.removeIf(c -> c.key().equals("value"));
                }
                rows = stock(parameters, names, cost);
            }
            case ReportCatalogue.TRADE_BY_DISTRIBUTOR -> {
                projection = "trade";
                rows = trade(parameters, names);
            }
            default -> {
                projection = "trade";
                rows = invoices(parameters, names);
            }
        }
        return new ReportTable(
                definition.reportId(),
                definition.titleId(),
                List.copyOf(columns),
                rows,
                totals(columns, rows),
                freshness(projection),
                clock.instant());
    }

    /** The report and its period guards, for the report and for a run request alike. */
    static Definition definition(String reportId) {
        return ReportCatalogue.find(reportId)
                .orElseThrow(
                        () -> new ProblemException("m8.report.unknown", Map.of("reportId", String.valueOf(reportId))));
    }

    static void checkPeriod(Definition definition, ReportParameters parameters) {
        if (!definition.period()) {
            return;
        }
        if (parameters.from() == null || parameters.to() == null) {
            throw new ProblemException("m8.report.period_required");
        }
        if (parameters.from().isAfter(parameters.to())) {
            throw new ProblemException("m8.report.period_invalid");
        }
    }

    /** The owner's own users and the Federation view see cost; a till, a counterparty, a grantee do not. */
    static boolean seesCost(ScopeContext scope) {
        return scope.deviceId() == null
                && (scope.policyClass() == PolicyClass.OWN || scope.policyClass() == PolicyClass.FEDERATION_VIEW);
    }

    private List<Map<String, String>> stock(ReportParameters parameters, Names names, boolean cost) {
        return jdbc.query(
                """
                select owner_entity_id, location_id, canonical_sku_id,
                       sum(qty_on_hand) as qty, sum(qty_on_hand * coalesce(unit_cost, 0)) as value
                  from reporting.stock_position
                 where (?::uuid is null or location_id = ?)
                 group by owner_entity_id, location_id, canonical_sku_id
                having sum(qty_on_hand) <> 0
                 order by owner_entity_id, location_id, canonical_sku_id
                """,
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

    private List<Map<String, String>> trade(ReportParameters parameters, Names names) {
        return jdbc.query(
                """
                select business_date, seller_entity_id, buyer_entity_id, sku_id, sum(qty) as qty, sum(value) as value
                  from reporting.trade_line_fact
                 where measure = 'RECEIVED' and business_date between ? and ?
                 group by business_date, seller_entity_id, buyer_entity_id, sku_id
                 order by business_date, seller_entity_id, buyer_entity_id, sku_id
                """,
                (rs, n) -> {
                    UUID sku = rs.getObject("sku_id", UUID.class);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("date", rs.getDate("business_date").toLocalDate().toString());
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

    private List<Map<String, String>> invoices(ReportParameters parameters, Names names) {
        // One row per invoice: the Federation view reads the seller's row only once, and a
        // buyer reads the seller's row through party_read.
        return jdbc.query(
                """
                select distinct on (business_date, doc_number, document_id)
                       business_date, doc_number, seller_entity_id, buyer_entity_id, net, tax, gross
                  from reporting.trade_document_event
                 where doc_type = 'INVOICE' and event_kind = 'ISSUED' and business_date between ? and ?
                 order by business_date, doc_number, document_id
                """,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("date", rs.getDate("business_date").toLocalDate().toString());
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

    private static Map<String, String> totals(List<Column> columns, List<Map<String, String>> rows) {
        Map<String, String> totals = new LinkedHashMap<>();
        for (Column column : columns) {
            if (!column.kind().equals(ReportCatalogue.QTY) && !column.kind().equals(ReportCatalogue.MONEY)) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, String> row : rows) {
                String value = row.get(column.key());
                if (value != null && !value.isEmpty()) {
                    sum = sum.add(new BigDecimal(value));
                }
            }
            totals.put(column.key(), plain(sum));
        }
        return totals;
    }

    private Instant freshness(String projection) {
        Timestamp latest = jdbc.queryForObject(
                "select max(last_event_at) from reporting.projection_state where name = ?",
                Timestamp.class,
                projection);
        return latest == null ? null : latest.toInstant();
    }

    // ---- the dashboard -----------------------------------------------------------------------

    @Override
    public Dashboard dashboard(ScopeContext scope) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        List<Dashboard.Tile> tiles = new ArrayList<>();
        tiles.add(new Dashboard.Tile(
                TILE_OPEN_ORDERS,
                "m8.tile.open_orders",
                count(
                        """
                        select count(distinct s.document_id) from reporting.trade_document_event s
                         where s.doc_type = 'ORDER' and s.event_kind = 'SUBMITTED'
                           and not exists (select 1 from reporting.trade_document_event x
                                            where x.document_id = s.document_id
                                              and x.event_kind in ('ACCEPTED', 'REJECTED', 'CANCELLED'))
                        """),
                "COUNT",
                null));
        tiles.add(new Dashboard.Tile(
                TILE_IN_TRANSIT,
                "m8.tile.deliveries_in_transit",
                count(
                        """
                        select count(distinct d.document_id) from reporting.trade_document_event d
                         where d.doc_type = 'DELIVERY_NOTE' and d.event_kind = 'DISPATCHED'
                           and not exists (select 1 from reporting.trade_document_event g
                                            where g.doc_type = 'GRN' and g.event_kind = 'CONFIRMED'
                                              and g.reference_document_id = d.document_id)
                        """),
                "COUNT",
                null));
        tiles.add(new Dashboard.Tile(
                TILE_GRNS_TODAY,
                "m8.tile.grns_today",
                count(
                        "select count(distinct document_id) from reporting.trade_document_event"
                                + " where doc_type = 'GRN' and event_kind = 'CONFIRMED' and business_date = ?",
                        Date.valueOf(today)),
                "COUNT",
                ReportCatalogue.TRADE_BY_DISTRIBUTOR));
        if (seesCost(scope)) {
            BigDecimal value = jdbc.queryForObject(
                    "select coalesce(sum(qty_on_hand * coalesce(unit_cost, 0)), 0) from reporting.stock_position",
                    BigDecimal.class);
            tiles.add(new Dashboard.Tile(
                    TILE_STOCK_VALUE, "m8.tile.stock_value", money(value), "MONEY", ReportCatalogue.STOCK_POSITION));
        }
        Timestamp latest =
                jdbc.queryForObject("select max(last_event_at) from reporting.projection_state", Timestamp.class);
        return new Dashboard(List.copyOf(tiles), latest == null ? null : latest.toInstant());
    }

    private BigDecimal count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return BigDecimal.valueOf(count == null ? 0 : count);
    }

    // ---- runs --------------------------------------------------------------------------------

    @Override
    public Optional<ReportRunView> run(UUID runId, ScopeContext scope) {
        return jdbc
                .query(
                        "select run_id, report_id, status, language, object_key, error_code, requested_at, completed_at"
                                + " from reporting.report_run where run_id = ?",
                        (rs, n) -> new ReportRunView(
                                rs.getObject("run_id", UUID.class),
                                rs.getString("report_id"),
                                rs.getString("status"),
                                rs.getString("language"),
                                rs.getString("object_key"),
                                rs.getString("error_code"),
                                rs.getTimestamp("requested_at").toInstant(),
                                rs.getTimestamp("completed_at") == null
                                        ? null
                                        : rs.getTimestamp("completed_at").toInstant()),
                        runId)
                .stream()
                .findFirst();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
