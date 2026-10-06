package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m8reporting.internal.projection.Projection;
import lk.coopfed.knoweb.m8reporting.internal.report.ReportCatalogue.Definition;
import lk.coopfed.knoweb.m8reporting.internal.report.TileCatalogue.TileDefinition;
import lk.coopfed.knoweb.m8reporting.query.Dashboard;
import lk.coopfed.knoweb.m8reporting.query.ExceptionItem;
import lk.coopfed.knoweb.m8reporting.query.ReportDefinitionView;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportRunView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
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
            @Value("${coop-erp.business-timezone}") String zone) {
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
}
