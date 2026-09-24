package lk.coopfed.knoweb.m1party.internal.bulk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.BulkRegisterEntities;
import lk.coopfed.knoweb.m1party.api.EntityRegistered;
import lk.coopfed.knoweb.m1party.api.ValidationReport;
import lk.coopfed.knoweb.m1party.api.ValidationReport.RowProblem;
import lk.coopfed.knoweb.m1party.api.ValidationReport.RowResult;
import lk.coopfed.knoweb.m1party.internal.entity.Entity;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRegistrar;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRepository;
import lk.coopfed.knoweb.m1party.internal.entity.FederationCallers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M1-11: registers the entities of a CSV file, all or none, and answers a validation report
 * (21A sections 3, 5, 8, 11). The guards: the caller is the Federation (once, for the file);
 * the file has the required columns and no more rows than {@code m1.bulk.max_rows}; every row
 * passes {@link RowValidators}. Then every row becomes an entity through the same rules as a
 * single registration ({@link EntityRegistrar}), written, audited and published here, in this
 * one transaction: one audit record and one {@code entity.registered.v1} per entity.
 *
 * <p>All or none, by design: a register of legal entities is corrected in the file and sent
 * again, and an operator never has to work out which half of a file went in. The report says
 * which rows and why, in file order, with a message id per problem for the screen to show.
 */
@Service
@CommandHandler(permission = "gov.entity.register")
class BulkRegisterEntitiesHandler implements Handles<BulkRegisterEntities, ValidationReport> {

    /** Configuration item, seeded by seed/m1party/config.yaml; a limit is never a literal (AGENTS.md). */
    static final String MAX_ROWS_SETTING = "m1.bulk.max_rows";

    static final String AUDIT_REGISTERED = "ENTITY_REGISTERED";

    private final EntityRepository repository;
    private final EntityRegistrar registrar;
    private final FederationCallers federation;
    private final ConfigRegistry config;
    private final AuditFacade audit;
    private final EventPublisher events;

    BulkRegisterEntitiesHandler(
            EntityRepository repository,
            EntityRegistrar registrar,
            FederationCallers federation,
            ConfigRegistry config,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.registrar = registrar;
        this.federation = federation;
        this.config = config;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public ValidationReport handle(BulkRegisterEntities command, ScopeContext scope) {

        // 1. the caller is the Federation, in an entity-wide OWN scope: once for the file.
        federation.require(scope);

        // 2. the file: not empty, the required columns, not more rows than allowed.
        CsvTable table = CsvTable.parse(command.csv() == null ? "" : command.csv());
        if (table.header().isEmpty() || table.rows().isEmpty()) {
            throw new ProblemException("bulk.file.empty");
        }
        List<String> missing = RowValidators.missingColumns(table.header());
        if (!missing.isEmpty()) {
            throw new ProblemException("bulk.file.header_invalid", Map.of("missing", String.join(", ", missing)));
        }
        int maxRows = config.getInt(MAX_ROWS_SETTING, scope, 500);
        if (table.rows().size() > maxRows) {
            throw new ProblemException(
                    "bulk.file.too_many_rows", Map.of("rows", table.rows().size(), "maxRows", maxRows));
        }

        // 3. every row, before any is registered.
        Set<String> codesSeen = RowValidators.codeSet();
        List<RowResult> results = new ArrayList<>();
        int rejected = 0;
        for (CsvTable.Row row : table.rows()) {
            List<RowProblem> problems = RowValidators.problemsOf(row, codesSeen, registrar::codeExists);
            if (problems.isEmpty()) {
                results.add(new RowResult(row.line(), row.get("entity_code"), RowResult.OK, null, List.of()));
            } else {
                rejected++;
                results.add(new RowResult(row.line(), row.get("entity_code"), RowResult.ERROR, null, problems));
            }
        }
        if (rejected > 0) {
            // Nothing was written, so there is nothing to roll back; the report is the answer.
            return new ValidationReport(ValidationReport.REJECTED, results.size(), 0, rejected, results);
        }

        // 4. all rows passed: each row becomes an entity; mutation, audit, event, in this transaction.
        List<RowResult> registered = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            Entity entity = registrar.prepare(RowValidators.commandOf(row));
            try {
                repository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException ex) {
                throw EntityRegistrar.translate(ex, entity);
            }
            audit.record(AUDIT_REGISTERED, Subject.of("entity", entity.getId()), null, entity.auditState(), scope);
            events.publish(new EntityRegistered(entity.getId(), entity.entityType()));
            registered.add(new RowResult(row.line(), entity.entityCode(), RowResult.OK, entity.getId(), List.of()));
        }
        return new ValidationReport(ValidationReport.REGISTERED, registered.size(), registered.size(), 0, registered);
    }
}
