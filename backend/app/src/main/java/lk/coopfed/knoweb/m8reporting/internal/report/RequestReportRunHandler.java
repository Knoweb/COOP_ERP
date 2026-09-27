package lk.coopfed.knoweb.m8reporting.internal.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m8reporting.api.ReportRunRequested;
import lk.coopfed.knoweb.m8reporting.api.RequestReportRun;
import lk.coopfed.knoweb.m8reporting.internal.report.ReportCatalogue.Definition;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RequestReportRun (28A section 5, "reports/{id}/runs post requestRun"; section 7,
 * render/ReportRunWorker): records a run REQUESTED for the caller's entity and asks the worker
 * to print it.
 *
 * <p>Guards: a user acting in an OWN scope ({@code m8.run.own_required}: the PDF belongs to an
 * entity, the renderer stores it under the scope's entity); the report known
 * ({@code m8.report.unknown}); the period of a period report given and in order
 * ({@code m8.report.period_required}, {@code m8.report.period_invalid}); a language the
 * catalogue has ({@code m8.run.language_invalid}). Audit {@code REPORT_RUN_REQUESTED}; event
 * {@code report_run.requested.v1}.
 */
@Service
@CommandHandler(permission = "rpt.export.run")
class RequestReportRunHandler implements Handles<RequestReportRun, UUID> {

    static final String AUDIT_REQUESTED = "REPORT_RUN_REQUESTED";
    static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;

    RequestReportRunHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RequestReportRun command, ScopeContext scope) {
        if (scope.userId() == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m8.run.own_required");
        }
        Definition definition = ReportQueriesImpl.definition(command.reportId());
        ReportQueriesImpl.checkPeriod(
                definition, new ReportParameters(command.from(), command.to(), command.locationId()));
        String language = command.language() == null ? scope.lang() : command.language();
        if (!LANGUAGES.contains(language)) {
            throw new ProblemException("m8.run.language_invalid", Map.of("language", language));
        }

        UUID runId = Ids.next();
        ObjectNode parameters = mapper.createObjectNode();
        if (definition.period()) {
            parameters.put("from", command.from().toString());
            parameters.put("to", command.to().toString());
        }
        if (definition.location() && command.locationId() != null) {
            parameters.put("locationId", command.locationId().toString());
        }
        jdbc.update(
                """
                insert into reporting.report_run
                       (run_id, report_id, requested_by, owner_entity_id, location_id, parameters, language, requested_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                runId,
                definition.reportId(),
                scope.userId(),
                scope.entityId(),
                scope.locationId(),
                parameters.toString(),
                language,
                Timestamp.from(clock.instant()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reportId", definition.reportId());
        after.put("parameters", parameters.toString());
        after.put("language", language);
        audit.record(AUDIT_REQUESTED, Subject.of("report_run", runId), null, after, scope);
        events.publish(new ReportRunRequested(
                runId,
                definition.reportId(),
                definition.period() ? command.from() : null,
                definition.period() ? command.to() : null,
                definition.location() ? command.locationId() : null,
                language));
        return runId;
    }
}
