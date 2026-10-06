package lk.coopfed.knoweb.m8reporting.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of M8 (28A section 4, "query/ ReportingQueries"): the report definitions, a report's
 * rows, the dashboard, the exception queue and the report runs. Every method takes the caller's
 * scope; row-level security decides which rows a report is made of, so a report never names an
 * owner.
 */
public interface ReportingQueries {

    /** The definitions the caller may run, in the order the catalogue lists them. */
    List<ReportDefinitionView> definitions(ScopeContext scope);

    /**
     * A report's rows for the parameters.
     *
     * @throws lk.coopfed.knoweb.kernel.api.ProblemException {@code m8.report.unknown},
     *     {@code m8.report.period_required}, {@code m8.report.period_invalid},
     *     {@code m8.report.period_too_long} ({@code reporting.max_period_days}),
     *     {@code m8.report.too_many_rows} ({@code reporting.max_rows})
     */
    ReportTable report(String reportId, ReportParameters parameters, ScopeContext scope);

    /** The dashboard's tiles for the caller's scope. */
    Dashboard dashboard(ScopeContext scope);

    /** The exception queue of the caller's scope: escalated first, then alerts, oldest first. */
    List<ExceptionItem> exceptions(ScopeContext scope);

    /** A report run the caller's entity requested. */
    Optional<ReportRunView> run(UUID runId, ScopeContext scope);

    /**
     * The latest print runs of a report the caller's entity requested, newest first.
     *
     * @throws lk.coopfed.knoweb.kernel.api.ProblemException {@code m8.report.unknown}
     */
    List<ReportRunView> runs(String reportId, ScopeContext scope);
}
