package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RetailPrice;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LocationSalesCost;
import lk.coopfed.knoweb.m8reporting.internal.projection.Projection;
import lk.coopfed.knoweb.m8reporting.internal.report.ReportCatalogue.Definition;
import lk.coopfed.knoweb.m8reporting.internal.report.TileCatalogue.TileDefinition;
import lk.coopfed.knoweb.m8reporting.query.AssetFilter;
import lk.coopfed.knoweb.m8reporting.query.AssetView;
import lk.coopfed.knoweb.m8reporting.query.DailyShopReport;
import lk.coopfed.knoweb.m8reporting.query.DailyShopReportItem;
import lk.coopfed.knoweb.m8reporting.query.DailyShopReportSummary;
import lk.coopfed.knoweb.m8reporting.query.Dashboard;
import lk.coopfed.knoweb.m8reporting.query.ExceptionItem;
import lk.coopfed.knoweb.m8reporting.query.MonthlyDailyTrendItem;
import lk.coopfed.knoweb.m8reporting.query.MonthlyProfitCenterPnlReport;
import lk.coopfed.knoweb.m8reporting.query.MonthlyShopReport;
import lk.coopfed.knoweb.m8reporting.query.MonthlyShopReportItem;
import lk.coopfed.knoweb.m8reporting.query.MonthlyShopReportSummary;
import lk.coopfed.knoweb.m8reporting.query.ProfitCenterPnlItem;
import lk.coopfed.knoweb.m8reporting.query.ProfitCenterView;
import lk.coopfed.knoweb.m8reporting.query.ReportDefinitionView;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportRunView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import lk.coopfed.knoweb.m8reporting.query.RevenueEstimatePlan;
import lk.coopfed.knoweb.m8reporting.query.RevenueEstimateResult;
import lk.coopfed.knoweb.m8reporting.query.ShopPerformanceState;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetFilter;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetPerformanceReport;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetPerformanceRow;
import lk.coopfed.knoweb.m8reporting.query.ShopTargetView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of M8 (28A section 7): the reports of the catalogue, the dashboard's tiles, the
 * exception queue and the report runs. Read-only transactions in the caller's scope: row-level
 * security decides which rows of the projections an answer is made of (the owner's, the
 * counterparty's through party_read, all of them for the Federation view), and no query names
 * an owner.
 *
 * <p>The dashboard is kept for {@code reporting.dashboard_cache_seconds} (60, 28A's "cached 60 s
 * per (tile, scope)") per scope and business day, and read again at once when this instance's
 * projections applied an event since.
 */
@Service
@Transactional(readOnly = true)
class ReportQueriesImpl implements ReportingQueries {

    static final String CACHE_SECONDS = "reporting.dashboard_cache_seconds";

    /** The longest period a report may cover, in days, first and last included (wave 2, M8-09). */
    static final String MAX_PERIOD_DAYS = "reporting.max_period_days";

    /** The most rows a report answers; more is refused, to be narrowed (wave 2, M8-09). */
    static final String MAX_ROWS = "reporting.max_rows";

    /** How many runs the run history lists. */
    static final int RUN_HISTORY = 20;

    private final JdbcTemplate jdbc;
    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final ReportCatalogue reports;
    private final ReportSources sources;
    private final TileCatalogue tiles;
    private final TileSources tileSources;
    private final ExceptionQueue exceptions;
    private final ConfigRegistry config;
    private final Clock clock;
    private final ZoneId zone;
    private final InventoryQueries inventoryQueries;
    private final PricingQueries pricing;

    /**
     * What a dashboard depends on: the caller's whole visibility, as the policies of M8 read it
     * (wave 2, M8-06): the class, the entity, the location, the granted entities of an external
     * grant (two auditors of one home entity with different grants see different rows, and a
     * revoked grant changes the key), whether a device asks, the language and the cost flag;
     * then the business day and this instance's applied-event count. No user: no M8 policy is per
     * user, so the user would only lower the hit rate.
     */
    private record CacheKey(
            PolicyClass policy,
            UUID entity,
            UUID location,
            Set<UUID> granted,
            boolean device,
            String lang,
            boolean cost,
            LocalDate day,
            long applied) {}

    private record Cached(Dashboard dashboard, Instant madeAt) {}

    private final Map<CacheKey, Cached> cache = new ConcurrentHashMap<>();

    ReportQueriesImpl(
            JdbcTemplate jdbc,
            PartyQueries party,
            CatalogueQueries catalogue,
            ReportCatalogue reports,
            ReportSources sources,
            TileCatalogue tiles,
            TileSources tileSources,
            ExceptionQueue exceptions,
            ConfigRegistry config,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone,
            InventoryQueries inventoryQueries,
            PricingQueries pricing) {
        this.jdbc = jdbc;
        this.party = party;
        this.catalogue = catalogue;
        this.reports = reports;
        this.sources = sources;
        this.tiles = tiles;
        this.tileSources = tileSources;
        this.exceptions = exceptions;
        this.config = config;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.inventoryQueries = inventoryQueries;
        this.pricing = pricing;
    }

    @Override
    public List<ReportDefinitionView> definitions(ScopeContext scope) {
        return reports.all().stream().map(Definition::view).toList();
    }

    @Override
    public ReportTable report(String reportId, ReportParameters parameters, ScopeContext scope) {
        Definition definition = definition(reports, reportId);
        checkPeriod(definition, parameters, config.getInt(MAX_PERIOD_DAYS, scope, 366));
        Names names = new Names(party, catalogue, scope);
        boolean cost = seesCost(scope);
        List<Column> columns = new ArrayList<>(definition.columns());
        if (!cost) {
            columns.removeIf(c -> definition.costColumns().contains(c.key()));
        }
        List<Map<String, String>> rows = sources.rows(
                definition.source(), parameters, names, cost, today(), config.getInt(MAX_ROWS, scope, 10_000));
        return new ReportTable(
                definition.reportId(),
                definition.titleId(),
                List.copyOf(columns),
                rows,
                totals(columns, rows),
                freshness(ReportSources.projection(definition.source())),
                clock.instant());
    }

    /** The report and its period guards, for the report and for a run request alike. */
    static Definition definition(ReportCatalogue reports, String reportId) {
        return reports.find(reportId)
                .orElseThrow(
                        () -> new ProblemException("m8.report.unknown", Map.of("reportId", String.valueOf(reportId))));
    }

    static void checkPeriod(Definition definition, ReportParameters parameters, int maxDays) {
        if (!definition.period()) {
            return;
        }
        if (parameters.from() == null || parameters.to() == null) {
            throw new ProblemException("m8.report.period_required");
        }
        if (parameters.from().isAfter(parameters.to())) {
            throw new ProblemException("m8.report.period_invalid");
        }
        if (ChronoUnit.DAYS.between(parameters.from(), parameters.to()) + 1 > maxDays) {
            throw new ProblemException("m8.report.period_too_long", Map.of("days", maxDays));
        }
    }

    /** The owner's own users and the Federation view see cost; a till, a counterparty, a grantee do not. */
    static boolean seesCost(ScopeContext scope) {
        return scope.deviceId() == null
                && (scope.policyClass() == PolicyClass.OWN || scope.policyClass() == PolicyClass.FEDERATION_VIEW);
    }

    /** The sums of the QTY, COUNT and MONEY columns; a rate is never added up. */
    private static Map<String, String> totals(List<Column> columns, List<Map<String, String>> rows) {
        Map<String, String> totals = new LinkedHashMap<>();
        for (Column column : columns) {
            if (!column.kind().equals(ReportCatalogue.QTY)
                    && !column.kind().equals(ReportCatalogue.MONEY)
                    && !column.kind().equals(ReportCatalogue.COUNT)) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, String> row : rows) {
                String value = row.get(column.key());
                if (value != null && !value.isEmpty()) {
                    sum = sum.add(new BigDecimal(value));
                }
            }
            totals.put(column.key(), sum.toPlainString());
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

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    // ---- the dashboard -----------------------------------------------------------------------

    @Override
    public Dashboard dashboard(ScopeContext scope) {
        LocalDate today = today();
        boolean cost = seesCost(scope);
        CacheKey key = new CacheKey(
                scope.policyClass(),
                scope.entityId(),
                scope.locationId(),
                scope.grantedEntities(),
                scope.deviceId() != null,
                scope.lang(),
                cost,
                today,
                Projection.applied());
        int seconds = config.getInt(CACHE_SECONDS, scope, 60);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && cached.madeAt().plusSeconds(seconds).isAfter(now)) {
            return cached.dashboard();
        }

        Names names = new Names(party, catalogue, scope);
        int open = exceptions.items(scope, names).size();
        TileSources.Context context = new TileSources.Context(scope, today, open);
        List<Dashboard.Tile> answer = new ArrayList<>();
        for (TileDefinition tile : tiles.all()) {
            if (tile.cost() && !cost) {
                continue;
            }
            TileSources.Value value = tileSources.value(tile.source(), context);
            if (value == null) {
                continue;
            }
            answer.add(new Dashboard.Tile(
                    tile.tileId(),
                    tile.labelId(),
                    value.value(),
                    tile.kind(),
                    tile.drill(),
                    tile.trend() ? value.trend() : List.of()));
        }
        Timestamp latest =
                jdbc.queryForObject("select max(last_event_at) from reporting.projection_state", Timestamp.class);
        Dashboard dashboard = new Dashboard(List.copyOf(answer), latest == null ? null : latest.toInstant());
        if (seconds > 0) {
            if (cache.size() > 1_000) {
                cache.clear();
            }
            cache.put(key, new Cached(dashboard, now));
        }
        return dashboard;
    }

    // ---- the exception queue -----------------------------------------------------------------

    @Override
    public List<ExceptionItem> exceptions(ScopeContext scope) {
        return exceptions.items(scope, new Names(party, catalogue, scope));
    }

    // ---- runs --------------------------------------------------------------------------------

    private static final RowMapper<ReportRunView> RUN = (rs, n) -> new ReportRunView(
            rs.getObject("run_id", UUID.class),
            rs.getString("report_id"),
            rs.getString("status"),
            rs.getString("language"),
            rs.getString("object_key"),
            rs.getString("error_code"),
            rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("completed_at") == null
                    ? null
                    : rs.getTimestamp("completed_at").toInstant());

    @Override
    public Optional<ReportRunView> run(UUID runId, ScopeContext scope) {
        return jdbc
                .query(
                        "select run_id, report_id, status, language, object_key, error_code, requested_at, completed_at"
                                + " from reporting.report_run where run_id = ?",
                        RUN,
                        runId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ReportRunView> runs(String reportId, ScopeContext scope) {
        definition(reports, reportId);
        return jdbc.query(
                "select run_id, report_id, status, language, object_key, error_code, requested_at, completed_at"
                        + " from reporting.report_run where report_id = ? order by requested_at desc, run_id desc"
                        + " limit " + RUN_HISTORY,
                RUN,
                reportId);
    }

    @Override
    public Optional<ShopTargetView> getShopTarget(UUID targetId, ScopeContext scope) {
        List<ShopTargetView> targets = jdbc.query(
                """
                select target_id, owner_entity_id, location_id, period_start, period_end,
                       target_amount, currency, created_at, updated_at
                  from reporting.shop_target
                 where target_id = ?
                """,
                (rs, rowNum) -> new ShopTargetView(
                        rs.getObject("target_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate(),
                        rs.getBigDecimal("target_amount"),
                        rs.getString("currency"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                targetId);
        return targets.stream().findFirst();
    }

    @Override
    public ShopTargetPerformanceReport shopTargetPerformance(ShopTargetFilter filter, ScopeContext scope) {
        Names names = new Names(party, catalogue, scope);
        UUID locationId = filter != null ? filter.locationId() : null;
        Date from = filter != null && filter.from() != null ? Date.valueOf(filter.from()) : null;
        Date to = filter != null && filter.to() != null ? Date.valueOf(filter.to()) : null;

        List<ShopTargetPerformanceRow> items = jdbc.query(
                """
                select t.target_id,
                       t.location_id,
                       t.period_start,
                       t.period_end,
                       t.target_amount,
                       t.currency,
                       t.created_at,
                       t.updated_at,
                       coalesce(sum(s.gross), 0) as actual_amount
                  from reporting.shop_target t
                  left join reporting.shop_sale_fact s
                    on s.owner_entity_id = t.owner_entity_id
                   and s.location_id = t.location_id
                   and s.business_date between t.period_start and t.period_end
                 where (?::uuid is null or t.location_id = ?)
                   and (?::date is null or t.period_start >= ?)
                   and (?::date is null or t.period_end <= ?)
                 group by t.target_id, t.location_id, t.period_start, t.period_end, t.target_amount, t.currency, t.created_at, t.updated_at
                 order by t.period_start desc, t.period_end desc, t.location_id
                """,
                (rs, rowNum) -> {
                    UUID targetId = rs.getObject("target_id", UUID.class);
                    UUID locId = rs.getObject("location_id", UUID.class);
                    LocalDate periodStart = rs.getDate("period_start").toLocalDate();
                    LocalDate periodEnd = rs.getDate("period_end").toLocalDate();
                    BigDecimal targetAmount = rs.getBigDecimal("target_amount").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal actualAmount = rs.getBigDecimal("actual_amount").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal varianceAmount =
                            actualAmount.subtract(targetAmount).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal achievementPercent = targetAmount.signum() > 0
                            ? actualAmount
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(targetAmount, 1, RoundingMode.HALF_UP)
                            : null;
                    String locationName = names.location(locId);
                    Optional<LocationView> locView = party.getLocation(locId, scope);
                    String locationCode =
                            locView.map(LocationView::locationCode).orElse("");

                    return new ShopTargetPerformanceRow(
                            targetId,
                            locId,
                            locationCode,
                            locationName,
                            periodStart,
                            periodEnd,
                            targetAmount,
                            actualAmount,
                            varianceAmount,
                            achievementPercent,
                            rs.getString("currency"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant());
                },
                locationId,
                locationId,
                from,
                from,
                to,
                to);
        return new ShopTargetPerformanceReport(items);
    }

    @Override
    public DailyShopReport dailyShopReport(LocalDate date, UUID locationId, ScopeContext scope) {
        if (scope.locationId() != null) {
            if (locationId != null && !scope.locationId().equals(locationId)) {
                throw new ProblemException("m8.shop_report.location_forbidden");
            }
            locationId = scope.locationId();
        }

        if (locationId != null) {
            Optional<LocationView> loc = party.getLocation(locationId, scope);
            if (loc.isEmpty()) {
                throw new ProblemException("m8.shop_report.location_unknown");
            }
        }

        LocalDate reportDate = date != null ? date : LocalDate.now(clock);
        Names names = new Names(party, catalogue, scope);

        // 1. Sales facts for the day
        record SalesFact(UUID locationId, long txnCount, BigDecimal gross, BigDecimal net, BigDecimal tax) {}
        Map<UUID, SalesFact> sales = new LinkedHashMap<>();
        jdbc.query(
                """
                select s.location_id,
                       count(s.receipt_id) as txn_count,
                       coalesce(sum(s.gross), 0) as gross,
                       coalesce(sum(s.net), 0) as net,
                       coalesce(sum(s.tax), 0) as tax
                  from reporting.shop_sale_fact s
                 where s.business_date = ?
                   and (?::uuid is null or s.location_id = ?)
                 group by s.location_id
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    sales.put(
                            locId,
                            new SalesFact(
                                    locId,
                                    rs.getLong("txn_count"),
                                    rs.getBigDecimal("gross"),
                                    rs.getBigDecimal("net"),
                                    rs.getBigDecimal("tax")));
                },
                Date.valueOf(reportDate),
                locationId,
                locationId);

        // 2. Line fact items sold for the day
        Map<UUID, BigDecimal> itemsSoldMap = new HashMap<>();
        jdbc.query(
                """
                select f.location_id,
                       coalesce(sum(f.qty), 0) as items_sold
                  from reporting.shop_sale_line_fact f
                 where f.business_date = ?
                   and (?::uuid is null or f.location_id = ?)
                 group by f.location_id
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    itemsSoldMap.put(locId, rs.getBigDecimal("items_sold"));
                },
                Date.valueOf(reportDate),
                locationId,
                locationId);

        // 3. Targets covering this day: pick the most specific target (smallest date range)
        record TargetRow(UUID locationId, BigDecimal targetAmount, LocalDate start, LocalDate end, String currency) {}
        Map<UUID, TargetRow> targetMap = new LinkedHashMap<>();
        jdbc.query(
                """
                select t.location_id,
                       t.target_amount,
                       t.period_start,
                       t.period_end,
                       t.currency
                  from reporting.shop_target t
                 where ? between t.period_start and t.period_end
                   and (?::uuid is null or t.location_id = ?)
                 order by (t.period_end - t.period_start) asc
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    if (!targetMap.containsKey(locId)) {
                        targetMap.put(
                                locId,
                                new TargetRow(
                                        locId,
                                        rs.getBigDecimal("target_amount"),
                                        rs.getDate("period_start").toLocalDate(),
                                        rs.getDate("period_end").toLocalDate(),
                                        rs.getString("currency")));
                    }
                },
                Date.valueOf(reportDate),
                locationId,
                locationId);

        // 4. Collect all relevant location IDs
        Set<UUID> allLocIds = new LinkedHashSet<>();
        if (locationId != null) {
            allLocIds.add(locationId);
        } else {
            allLocIds.addAll(sales.keySet());
            allLocIds.addAll(targetMap.keySet());
            try {
                party.listLocations(new LocationFilter("ACTIVE", null, null, null, 100), scope)
                        .items()
                        .forEach(l -> allLocIds.add(l.locationId()));
            } catch (Exception ignored) {
            }
        }

        List<DailyShopReportItem> items = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalNet = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalTax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        long totalTxns = 0;
        BigDecimal totalItems = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        BigDecimal totalTarget = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        boolean anyTarget = false;

        for (UUID locId : allLocIds) {
            SalesFact sf = sales.get(locId);
            BigDecimal gross = sf != null
                    ? sf.gross().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = sf != null
                    ? sf.net().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = sf != null
                    ? sf.tax().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            long txnCount = sf != null ? sf.txnCount() : 0;
            BigDecimal atv = txnCount > 0
                    ? gross.divide(BigDecimal.valueOf(txnCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal itemsSold =
                    itemsSoldMap.getOrDefault(locId, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);

            TargetRow tr = targetMap.get(locId);
            BigDecimal dailyTarget = null;
            BigDecimal variance = null;
            BigDecimal achievement = null;
            ShopPerformanceState state = ShopPerformanceState.NO_TARGET;
            String currency = "LKR";

            if (tr != null) {
                anyTarget = true;
                currency = tr.currency();
                long days = ChronoUnit.DAYS.between(tr.start(), tr.end()) + 1;
                dailyTarget = tr.targetAmount().divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
                totalTarget = totalTarget.add(dailyTarget);
                variance = gross.subtract(dailyTarget).setScale(2, RoundingMode.HALF_UP);
                if (dailyTarget.signum() > 0) {
                    achievement = gross.multiply(BigDecimal.valueOf(100)).divide(dailyTarget, 1, RoundingMode.HALF_UP);
                }
                state = gross.compareTo(dailyTarget) >= 0
                        ? ShopPerformanceState.TARGET_MET
                        : ShopPerformanceState.BEHIND_TARGET;
            }

            Optional<LocationView> locView = party.getLocation(locId, scope);
            String locationCode = locView.map(LocationView::locationCode).orElse("");
            String locationName = names.location(locId);

            items.add(new DailyShopReportItem(
                    locId,
                    locationCode,
                    locationName,
                    reportDate,
                    gross,
                    net,
                    tax,
                    txnCount,
                    atv,
                    itemsSold,
                    dailyTarget,
                    variance,
                    achievement,
                    state,
                    currency));

            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
            totalTax = totalTax.add(tax);
            totalTxns += txnCount;
            totalItems = totalItems.add(itemsSold);
        }

        BigDecimal avgAtv = totalTxns > 0
                ? totalGross.divide(BigDecimal.valueOf(totalTxns), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal summaryTarget = anyTarget ? totalTarget : null;
        BigDecimal summaryVariance =
                anyTarget ? totalGross.subtract(totalTarget).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal summaryAchievement = anyTarget && totalTarget.signum() > 0
                ? totalGross.multiply(BigDecimal.valueOf(100)).divide(totalTarget, 1, RoundingMode.HALF_UP)
                : null;

        DailyShopReportSummary summary = new DailyShopReportSummary(
                reportDate,
                totalGross,
                totalNet,
                totalTax,
                totalTxns,
                avgAtv,
                totalItems,
                summaryTarget,
                summaryVariance,
                summaryAchievement,
                "LKR");

        return new DailyShopReport(summary, items);
    }

    @Override
    public MonthlyShopReport monthlyShopReport(String yearMonth, UUID locationId, ScopeContext scope) {
        if (scope.locationId() != null) {
            if (locationId != null && !scope.locationId().equals(locationId)) {
                throw new ProblemException("m8.shop_report.location_forbidden");
            }
            locationId = scope.locationId();
        }

        if (locationId != null) {
            Optional<LocationView> loc = party.getLocation(locationId, scope);
            if (loc.isEmpty()) {
                throw new ProblemException("m8.shop_report.location_unknown");
            }
        }

        YearMonth ym;
        if (yearMonth == null || yearMonth.isBlank()) {
            ym = YearMonth.now(clock);
        } else {
            try {
                ym = YearMonth.parse(yearMonth);
            } catch (Exception e) {
                throw new ProblemException("m8.shop_report.month_invalid");
            }
        }

        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();
        Names names = new Names(party, catalogue, scope);

        // 1. Monthly sales per shop
        record ShopMonthlySales(UUID locationId, long txnCount, BigDecimal gross, BigDecimal net, BigDecimal tax) {}
        Map<UUID, ShopMonthlySales> sales = new LinkedHashMap<>();
        jdbc.query(
                """
                select s.location_id,
                       count(s.receipt_id) as txn_count,
                       coalesce(sum(s.gross), 0) as gross,
                       coalesce(sum(s.net), 0) as net,
                       coalesce(sum(s.tax), 0) as tax
                  from reporting.shop_sale_fact s
                 where s.business_date between ? and ?
                   and (?::uuid is null or s.location_id = ?)
                 group by s.location_id
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    sales.put(
                            locId,
                            new ShopMonthlySales(
                                    locId,
                                    rs.getLong("txn_count"),
                                    rs.getBigDecimal("gross"),
                                    rs.getBigDecimal("net"),
                                    rs.getBigDecimal("tax")));
                },
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd),
                locationId,
                locationId);

        // 2. Line fact items sold per shop
        Map<UUID, BigDecimal> itemsSoldMap = new HashMap<>();
        jdbc.query(
                """
                select f.location_id,
                       coalesce(sum(f.qty), 0) as items_sold
                  from reporting.shop_sale_line_fact f
                 where f.business_date between ? and ?
                   and (?::uuid is null or f.location_id = ?)
                 group by f.location_id
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    itemsSoldMap.put(locId, rs.getBigDecimal("items_sold"));
                },
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd),
                locationId,
                locationId);

        // 3. Daily trend across the month
        Map<LocalDate, MonthlyDailyTrendItem> dailyTrendMap = new LinkedHashMap<>();
        for (LocalDate d = periodStart; !d.isAfter(periodEnd); d = d.plusDays(1)) {
            dailyTrendMap.put(
                    d,
                    new MonthlyDailyTrendItem(
                            d,
                            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                            0,
                            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)));
        }

        Map<LocalDate, BigDecimal> dailyItemsMap = new HashMap<>();
        jdbc.query(
                """
                select f.business_date,
                       coalesce(sum(f.qty), 0) as items_sold
                  from reporting.shop_sale_line_fact f
                 where f.business_date between ? and ?
                   and (?::uuid is null or f.location_id = ?)
                 group by f.business_date
                """,
                rs -> {
                    dailyItemsMap.put(rs.getDate("business_date").toLocalDate(), rs.getBigDecimal("items_sold"));
                },
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd),
                locationId,
                locationId);

        jdbc.query(
                """
                select s.business_date,
                       count(s.receipt_id) as txn_count,
                       coalesce(sum(s.gross), 0) as gross,
                       coalesce(sum(s.net), 0) as net,
                       coalesce(sum(s.tax), 0) as tax
                  from reporting.shop_sale_fact s
                 where s.business_date between ? and ?
                   and (?::uuid is null or s.location_id = ?)
                 group by s.business_date
                 order by s.business_date
                """,
                rs -> {
                    LocalDate d = rs.getDate("business_date").toLocalDate();
                    long txns = rs.getLong("txn_count");
                    BigDecimal gross = rs.getBigDecimal("gross").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal net = rs.getBigDecimal("net").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal tax = rs.getBigDecimal("tax").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal atv = txns > 0
                            ? gross.divide(BigDecimal.valueOf(txns), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    BigDecimal items =
                            dailyItemsMap.getOrDefault(d, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
                    dailyTrendMap.put(d, new MonthlyDailyTrendItem(d, gross, net, tax, txns, atv, items));
                },
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd),
                locationId,
                locationId);

        // 4. Targets overlapping the month per location
        Map<UUID, BigDecimal> shopTargetMap = new HashMap<>();
        Map<UUID, String> shopCurrencyMap = new HashMap<>();
        jdbc.query(
                """
                select t.location_id,
                       t.period_start,
                       t.period_end,
                       t.target_amount,
                       t.currency
                  from reporting.shop_target t
                 where t.period_start <= ?
                   and t.period_end >= ?
                   and (?::uuid is null or t.location_id = ?)
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    LocalDate tStart = rs.getDate("period_start").toLocalDate();
                    LocalDate tEnd = rs.getDate("period_end").toLocalDate();
                    BigDecimal amt = rs.getBigDecimal("target_amount");
                    shopCurrencyMap.put(locId, rs.getString("currency"));

                    LocalDate overlapStart = tStart.isAfter(periodStart) ? tStart : periodStart;
                    LocalDate overlapEnd = tEnd.isBefore(periodEnd) ? tEnd : periodEnd;
                    long overlapDays = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
                    long totalDays = ChronoUnit.DAYS.between(tStart, tEnd) + 1;

                    BigDecimal prorated = amt.multiply(BigDecimal.valueOf(overlapDays))
                            .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
                    shopTargetMap.merge(locId, prorated, BigDecimal::add);
                },
                Date.valueOf(periodEnd),
                Date.valueOf(periodStart),
                locationId,
                locationId);

        // 5. Collect shops
        Set<UUID> allLocIds = new LinkedHashSet<>();
        if (locationId != null) {
            allLocIds.add(locationId);
        } else {
            allLocIds.addAll(sales.keySet());
            allLocIds.addAll(shopTargetMap.keySet());
            try {
                party.listLocations(new LocationFilter("ACTIVE", null, null, null, 100), scope)
                        .items()
                        .forEach(l -> allLocIds.add(l.locationId()));
            } catch (Exception ignored) {
            }
        }

        List<MonthlyShopReportItem> items = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalNet = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalTax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        long totalTxns = 0;
        BigDecimal totalItems = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        BigDecimal totalTarget = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        boolean anyTarget = false;

        for (UUID locId : allLocIds) {
            ShopMonthlySales sf = sales.get(locId);
            BigDecimal gross = sf != null
                    ? sf.gross().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = sf != null
                    ? sf.net().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = sf != null
                    ? sf.tax().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            long txnCount = sf != null ? sf.txnCount() : 0;
            BigDecimal atv = txnCount > 0
                    ? gross.divide(BigDecimal.valueOf(txnCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal itemsSold =
                    itemsSoldMap.getOrDefault(locId, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);

            BigDecimal shopTarget = shopTargetMap.get(locId);
            BigDecimal variance = null;
            BigDecimal achievement = null;
            ShopPerformanceState state = ShopPerformanceState.NO_TARGET;
            String currency = shopCurrencyMap.getOrDefault(locId, "LKR");

            if (shopTarget != null) {
                anyTarget = true;
                shopTarget = shopTarget.setScale(2, RoundingMode.HALF_UP);
                totalTarget = totalTarget.add(shopTarget);
                variance = gross.subtract(shopTarget).setScale(2, RoundingMode.HALF_UP);
                if (shopTarget.signum() > 0) {
                    achievement = gross.multiply(BigDecimal.valueOf(100)).divide(shopTarget, 1, RoundingMode.HALF_UP);
                }
                state = gross.compareTo(shopTarget) >= 0
                        ? ShopPerformanceState.TARGET_MET
                        : ShopPerformanceState.BEHIND_TARGET;
            }

            Optional<LocationView> locView = party.getLocation(locId, scope);
            String locationCode = locView.map(LocationView::locationCode).orElse("");
            String locationName = names.location(locId);

            items.add(new MonthlyShopReportItem(
                    locId,
                    locationCode,
                    locationName,
                    gross,
                    net,
                    tax,
                    txnCount,
                    atv,
                    itemsSold,
                    shopTarget,
                    variance,
                    achievement,
                    state,
                    currency));

            totalGross = totalGross.add(gross);
            totalNet = totalNet.add(net);
            totalTax = totalTax.add(tax);
            totalTxns += txnCount;
            totalItems = totalItems.add(itemsSold);
        }

        BigDecimal avgAtv = totalTxns > 0
                ? totalGross.divide(BigDecimal.valueOf(totalTxns), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal summaryTarget = anyTarget ? totalTarget : null;
        BigDecimal summaryVariance =
                anyTarget ? totalGross.subtract(totalTarget).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal summaryAchievement = anyTarget && totalTarget.signum() > 0
                ? totalGross.multiply(BigDecimal.valueOf(100)).divide(totalTarget, 1, RoundingMode.HALF_UP)
                : null;

        MonthlyShopReportSummary summary = new MonthlyShopReportSummary(
                ym.toString(),
                periodStart,
                periodEnd,
                totalGross,
                totalNet,
                totalTax,
                totalTxns,
                avgAtv,
                totalItems,
                summaryTarget,
                summaryVariance,
                summaryAchievement,
                "LKR");

        return new MonthlyShopReport(summary, new ArrayList<>(dailyTrendMap.values()), items);
    }

    @Override
    public List<ProfitCenterView> listProfitCenters(ScopeContext scope) {
        Names names = new Names(party, catalogue, scope);
        return jdbc.query(
                """
                select profit_center_id, owner_entity_id, code, name, category, location_id,
                       status, created_at
                  from reporting.profit_center
                 where status = 'ACTIVE'
                 order by code
                """,
                (rs, n) -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    String locName = locId != null ? names.location(locId) : null;
                    return new ProfitCenterView(
                            rs.getObject("profit_center_id", UUID.class),
                            rs.getObject("owner_entity_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("category"),
                            locId,
                            locName,
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant());
                });
    }

    @Override
    public MonthlyProfitCenterPnlReport monthlyProfitCenterPnl(
            String yearMonth, UUID profitCenterId, ScopeContext scope) {
        YearMonth ym;
        if (yearMonth == null || yearMonth.isBlank()) {
            ym = YearMonth.now(clock);
        } else {
            try {
                ym = YearMonth.parse(yearMonth);
            } catch (Exception e) {
                throw new ProblemException("m8.profit_center.month_invalid");
            }
        }
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();

        List<ProfitCenterView> allCenters = listProfitCenters(scope);
        if (profitCenterId != null) {
            allCenters = allCenters.stream()
                    .filter(c -> c.profitCenterId().equals(profitCenterId))
                    .toList();
            if (allCenters.isEmpty()) {
                throw new ProblemException("m8.profit_center.not_found");
            }
        }

        record LocationSales(UUID locationId, BigDecimal netSales, long txnCount) {}
        Map<UUID, LocationSales> salesByLocation = new LinkedHashMap<>();
        jdbc.query(
                """
                select location_id,
                       coalesce(sum(net), 0) as net_sales,
                       count(receipt_id) as txn_count
                  from reporting.shop_sale_fact
                 where business_date between ? and ?
                 group by location_id
                """,
                rs -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    salesByLocation.put(
                            locId, new LocationSales(locId, rs.getBigDecimal("net_sales"), rs.getLong("txn_count")));
                },
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd));

        Map<UUID, LocationSalesCost> costByLocation =
                inventoryQueries.locationsSalesCost(periodStart, periodEnd, scope);

        Set<UUID> mappedLocations = new HashSet<>();
        List<ProfitCenterPnlItem> items = new ArrayList<>();

        for (ProfitCenterView pc : allCenters) {
            UUID locId = pc.locationId();
            BigDecimal revenue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            long txnCount = 0;
            if (locId != null) {
                mappedLocations.add(locId);
                LocationSales ls = salesByLocation.get(locId);
                if (ls != null) {
                    revenue = ls.netSales() != null ? ls.netSales().setScale(2, RoundingMode.HALF_UP) : revenue;
                    txnCount = ls.txnCount();
                }
            }

            LocationSalesCost lc = locId != null ? costByLocation.get(locId) : null;
            boolean costPending = false;
            BigDecimal cogs = null;
            BigDecimal grossProfit = null;
            BigDecimal marginPercent = null;

            if (locId != null) {
                if (revenue.compareTo(BigDecimal.ZERO) > 0 && (lc == null || lc.movementCount() == 0)) {
                    // Revenue exists but no inventory movement cost recognized -> flag pending
                    costPending = true;
                } else if (lc != null && lc.costPending()) {
                    costPending = true;
                } else {
                    cogs = (lc != null && lc.recognizedCost() != null)
                            ? lc.recognizedCost().setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    grossProfit = revenue.subtract(cogs).setScale(2, RoundingMode.HALF_UP);
                    marginPercent = revenue.compareTo(BigDecimal.ZERO) > 0
                            ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }
            } else {
                cogs = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                grossProfit = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                marginPercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }

            items.add(new ProfitCenterPnlItem(
                    pc.profitCenterId(),
                    pc.code(),
                    pc.name(),
                    pc.category(),
                    locId,
                    pc.locationName(),
                    revenue,
                    cogs,
                    grossProfit,
                    marginPercent,
                    costPending,
                    txnCount));
        }

        ProfitCenterPnlItem unallocated = null;
        BigDecimal unallocatedRev = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal unallocatedCost = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        long unallocatedTxns = 0;
        boolean unallocatedPending = false;

        if (profitCenterId == null) {
            Set<UUID> allActiveLocations = new HashSet<>();
            allActiveLocations.addAll(salesByLocation.keySet());
            allActiveLocations.addAll(costByLocation.keySet());
            allActiveLocations.removeAll(mappedLocations);

            if (!allActiveLocations.isEmpty()) {
                for (UUID unmappedLoc : allActiveLocations) {
                    LocationSales ls = salesByLocation.get(unmappedLoc);
                    if (ls != null) {
                        if (ls.netSales() != null) {
                            unallocatedRev = unallocatedRev.add(ls.netSales());
                        }
                        unallocatedTxns += ls.txnCount();
                    }
                    LocationSalesCost lc = costByLocation.get(unmappedLoc);
                    if (ls != null
                            && ls.netSales() != null
                            && ls.netSales().compareTo(BigDecimal.ZERO) > 0
                            && (lc == null || lc.movementCount() == 0)) {
                        unallocatedPending = true;
                    } else if (lc != null && lc.costPending()) {
                        unallocatedPending = true;
                    } else if (lc != null && lc.recognizedCost() != null) {
                        unallocatedCost = unallocatedCost.add(lc.recognizedCost());
                    }
                }
                BigDecimal unallocProfit = !unallocatedPending ? unallocatedRev.subtract(unallocatedCost) : null;
                BigDecimal unallocMargin = (!unallocatedPending && unallocatedRev.compareTo(BigDecimal.ZERO) > 0)
                        ? unallocProfit
                                .multiply(BigDecimal.valueOf(100))
                                .divide(unallocatedRev, 2, RoundingMode.HALF_UP)
                        : (!unallocatedPending ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : null);

                unallocated = new ProfitCenterPnlItem(
                        null,
                        "UNALLOCATED",
                        "Unallocated / General",
                        "GENERAL",
                        null,
                        null,
                        unallocatedRev.setScale(2, RoundingMode.HALF_UP),
                        !unallocatedPending ? unallocatedCost.setScale(2, RoundingMode.HALF_UP) : null,
                        unallocProfit != null ? unallocProfit.setScale(2, RoundingMode.HALF_UP) : null,
                        unallocMargin != null ? unallocMargin.setScale(2, RoundingMode.HALF_UP) : null,
                        unallocatedPending,
                        unallocatedTxns);
            }
        }

        BigDecimal totalRevenue = items.stream()
                .map(ProfitCenterPnlItem::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(unallocated != null ? unallocated.revenue() : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        boolean totalCostPending = items.stream().anyMatch(ProfitCenterPnlItem::costPending)
                || (unallocated != null && unallocated.costPending());

        BigDecimal totalCost = null;
        BigDecimal grossProfit = null;
        BigDecimal marginPercent = null;

        if (!totalCostPending) {
            BigDecimal itemsCost = items.stream()
                    .map(ProfitCenterPnlItem::cost)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (unallocated != null && unallocated.cost() != null) {
                itemsCost = itemsCost.add(unallocated.cost());
            }
            totalCost = itemsCost.setScale(2, RoundingMode.HALF_UP);
            grossProfit = totalRevenue.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
            marginPercent = totalRevenue.compareTo(BigDecimal.ZERO) > 0
                    ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        MonthlyProfitCenterPnlReport.Summary summary = new MonthlyProfitCenterPnlReport.Summary(
                totalRevenue, totalCost, grossProfit, marginPercent, totalCostPending);

        return new MonthlyProfitCenterPnlReport(ym.toString(), "LKR", summary, items, unallocated);
    }

    @Override
    public List<AssetView> listAssets(AssetFilter filter, ScopeContext scope) {
        Names names = new Names(party, catalogue, scope);
        StringBuilder sql = new StringBuilder(
                """
                select asset_id, owner_entity_id, asset_code, name, description, asset_type,
                       location_id, acquisition_date, acquisition_cost, status, created_at
                  from reporting.asset
                 where 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            if (filter.locationId() != null) {
                sql.append(" and location_id = ?");
                params.add(filter.locationId());
            }
            if (filter.assetType() != null && !filter.assetType().isBlank()) {
                sql.append(" and asset_type = ?");
                params.add(filter.assetType().toUpperCase());
            }
            if (filter.status() != null && !filter.status().isBlank()) {
                sql.append(" and status = ?");
                params.add(filter.status().toUpperCase());
            }
        }
        sql.append(" order by asset_code");

        return jdbc.query(
                sql.toString(),
                (rs, n) -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    String locName = locId != null ? names.location(locId) : null;
                    Date acqDate = rs.getDate("acquisition_date");
                    return new AssetView(
                            rs.getObject("asset_id", UUID.class),
                            rs.getObject("owner_entity_id", UUID.class),
                            rs.getString("asset_code"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("asset_type"),
                            locId,
                            locName,
                            acqDate != null ? acqDate.toLocalDate() : null,
                            rs.getBigDecimal("acquisition_cost"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant());
                },
                params.toArray());
    }

    @Override
    public Optional<AssetView> getAsset(UUID assetId, ScopeContext scope) {
        if (assetId == null) {
            return Optional.empty();
        }
        Names names = new Names(party, catalogue, scope);
        List<AssetView> found = jdbc.query(
                """
                select asset_id, owner_entity_id, asset_code, name, description, asset_type,
                       location_id, acquisition_date, acquisition_cost, status, created_at
                  from reporting.asset
                 where asset_id = ?
                """,
                (rs, n) -> {
                    UUID locId = rs.getObject("location_id", UUID.class);
                    String locName = locId != null ? names.location(locId) : null;
                    Date acqDate = rs.getDate("acquisition_date");
                    return new AssetView(
                            rs.getObject("asset_id", UUID.class),
                            rs.getObject("owner_entity_id", UUID.class),
                            rs.getString("asset_code"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("asset_type"),
                            locId,
                            locName,
                            acqDate != null ? acqDate.toLocalDate() : null,
                            rs.getBigDecimal("acquisition_cost"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant());
                },
                assetId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public RevenueEstimateResult estimateRevenue(RevenueEstimatePlan plan, ScopeContext scope) {
        if (scope.policyClass() == PolicyClass.NONE) {
            throw new ProblemException("permission.denied");
        }
        if (plan.targetRevenue() == null || plan.targetRevenue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProblemException("m8.estimator.target_revenue_invalid");
        }
        if (plan.items() == null || plan.items().isEmpty()) {
            throw new ProblemException("m8.estimator.mix_empty");
        }

        Set<UUID> seenSkus = new HashSet<>();
        for (RevenueEstimatePlan.MixItem item : plan.items()) {
            if (item.skuId() == null || !seenSkus.add(item.skuId())) {
                throw new ProblemException("m8.estimator.duplicate_sku");
            }
            if (item.mixPercent() == null || item.mixPercent().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ProblemException("m8.estimator.mix_must_equal_100");
            }
        }

        BigDecimal totalMix = plan.items().stream()
                .map(RevenueEstimatePlan.MixItem::mixPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (totalMix.compareTo(new BigDecimal("100.00")) != 0) {
            throw new ProblemException("m8.estimator.mix_must_equal_100");
        }

        UUID locationId = plan.locationId();
        if (scope.locationId() != null) {
            if (locationId != null && !scope.locationId().equals(locationId)) {
                throw new ProblemException("m8.estimator.location_forbidden");
            }
            locationId = scope.locationId();
        }
        if (locationId == null) {
            throw new ProblemException("m8.estimator.location_required");
        }

        Optional<LocationView> locOpt = party.getLocation(locationId, scope);
        if (locOpt.isEmpty()) {
            throw new ProblemException("m8.estimator.location_unknown");
        }

        LocalDate businessDate =
                plan.businessDate() != null ? plan.businessDate() : LocalDate.now(clock.withZone(zone));
        String priceType = "COMMERCIAL".equalsIgnoreCase(plan.priceType()) ? "COMMERCIAL" : "RETAIL";

        List<RevenueEstimateResult.EstimatedItem> calculatedItems = new ArrayList<>();
        BigDecimal totalEstimatedRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        Names names = new Names(party, catalogue, scope);

        for (RevenueEstimatePlan.MixItem item : plan.items()) {
            Optional<SkuView> skuOpt = catalogue.getSku(item.skuId(), scope);
            if (skuOpt.isEmpty() || "INACTIVE".equalsIgnoreCase(skuOpt.get().status())) {
                throw new ProblemException(
                        "m8.estimator.sku_not_found",
                        Map.of("skuId", item.skuId().toString()));
            }
            SkuView sku = skuOpt.get();

            Optional<RetailPrice> priceOpt;
            if ("COMMERCIAL".equals(priceType)) {
                priceOpt = pricing.resolveCommercialPrice(
                        locationId, sku.skuId(), sku.baseUomCode(), BigDecimal.ONE, businessDate, scope);
            } else {
                priceOpt = pricing.resolveRetailPrice(
                        locationId, sku.skuId(), sku.baseUomCode(), BigDecimal.ONE, businessDate, scope);
            }

            if (priceOpt.isEmpty()
                    || !priceOpt.get().sellable()
                    || priceOpt.get().unitPrice() == null
                    || priceOpt.get().unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ProblemException(
                        "m8.estimator.unresolved_price",
                        Map.of("skuId", item.skuId().toString(), "skuCode", sku.skuCode()));
            }

            RetailPrice resolved = priceOpt.get();
            BigDecimal unitPrice = resolved.unitPrice().setScale(4, RoundingMode.HALF_UP);

            BigDecimal targetShare = plan.targetRevenue()
                    .multiply(item.mixPercent())
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

            BigDecimal rawQty = targetShare.divide(unitPrice, 4, RoundingMode.HALF_UP);

            BigDecimal roundedQty;
            if (sku.soldByWeight()) {
                roundedQty = rawQty.setScale(3, RoundingMode.HALF_UP);
            } else {
                roundedQty = rawQty.setScale(0, RoundingMode.HALF_UP);
            }

            BigDecimal estimatedRevenue = roundedQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal roundingVariance = estimatedRevenue.subtract(targetShare).setScale(2, RoundingMode.HALF_UP);

            totalEstimatedRevenue = totalEstimatedRevenue.add(estimatedRevenue);

            String skuName = names.skuName(sku.skuId());
            String priceSource = resolved.capReason() != null ? resolved.capReason() : priceType;

            calculatedItems.add(new RevenueEstimateResult.EstimatedItem(
                    sku.skuId(),
                    sku.skuCode(),
                    skuName,
                    sku.baseUomCode(),
                    unitPrice,
                    priceSource,
                    item.mixPercent().setScale(2, RoundingMode.HALF_UP),
                    targetShare,
                    rawQty,
                    roundedQty,
                    estimatedRevenue,
                    roundingVariance));
        }

        BigDecimal targetVariance =
                totalEstimatedRevenue.subtract(plan.targetRevenue().setScale(2, RoundingMode.HALF_UP));

        RevenueEstimateResult.Assumptions assumptions = new RevenueEstimateResult.Assumptions(
                plan.targetRevenue().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                locationId,
                priceType,
                businessDate.toString(),
                "HALF_UP to unit precision (discrete items: integer, weighted items: 3 decimals)",
                "COMMERCIAL".equals(priceType)
                        ? "Commercial list in force with retail fallback, bounded by MRP and control price"
                        : "Retail list in force, bounded by in-stock MRP and control price");

        return new RevenueEstimateResult(
                plan.targetRevenue().setScale(2, RoundingMode.HALF_UP),
                totalEstimatedRevenue,
                targetVariance,
                totalMix,
                "LKR",
                locationId,
                priceType,
                businessDate,
                calculatedItems,
                assumptions);
    }
}
