package lk.coopfed.knoweb.m9integration.internal.journal;

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
import java.util.TreeMap;
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
import lk.coopfed.knoweb.m9integration.api.JournalExportGenerated;
import lk.coopfed.knoweb.m9integration.api.RequestJournalExport;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RequestJournalExport with the JournalBuilder (29A sections 6 and 6.2), in the request: the
 * demo's volume is a few hundred postings, so the export is GENERATED before the answer and the
 * worker is not needed (decided on the architect's delegation, 29 September 2026; README).
 *
 * <p>Guards, in order: a user acting for the whole entity, OWN and not at one location
 * ({@code m9.journal.entity_required}); the period given and in order
 * ({@code m9.journal.period_invalid}); the period closed, that is {@code periodTo} before today in
 * the business time zone, unless the request says {@code provisional} ({@code
 * m9.journal.period_open}; wave 2, CR-29-1 item 1); postings in the period that no export took
 * ({@code m9.journal.nothing_to_export}). 29A's overlap guard is replaced by the rule that makes it
 * unnecessary: every posting is exported once (journal_line.posting_id is unique), so an export
 * over a period already exported takes only what arrived since, which is the supplement; a
 * provisional export is a first instalment of its period, never superseded.
 *
 * <p>TODO(CR-29-1, {@code 2026-10-06-wave2-journal-export.md} (2)): when location-dated postings
 * reach M9 (the buyer's GRN today, M5 write-offs and adjustments later), "closed" extends to every
 * location of the entity having {@code BusinessDate.current(location) > periodTo}, which needs
 * {@code m1party::query} among M9's allowed dependencies; whether one shop offline for two days
 * should block the entity's final export is open for the architect.
 *
 * <p>Mutation: under a lock per entity (two clicks never race for the same postings), the
 * postings in date and document order become the export's lines. Each line debits one role and
 * credits another with one amount, so the journal balances by construction; it is checked all the
 * same, role by role, and a difference (a defect, never data) refuses the export
 * ({@code m9.journal.unbalanced}). The file is written once, with the current format version,
 * hashed, and stored with the export ({@code journal_export_file}, CR-29-1 item 2): a download
 * serves those bytes, byte for byte. Audit JOURNAL_EXPORT_GENERATED; event journal.generated.v1;
 * both carry the provisional flag.
 */
@Service
@CommandHandler(permission = "int.journal.export")
class RequestJournalExportHandler implements Handles<RequestJournalExport, UUID> {

    static final String AUDIT_GENERATED = "JOURNAL_EXPORT_GENERATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    RequestJournalExportHandler(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    private record Posting(UUID postingId, JournalLineView line) {}

    @Override
    @Transactional
    public UUID handle(RequestJournalExport command, ScopeContext scope) {
        if (scope.userId() == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || scope.locationId() != null) {
            throw new ProblemException("m9.journal.entity_required");
        }
        if (command == null
                || command.periodFrom() == null
                || command.periodTo() == null
                || command.periodFrom().isAfter(command.periodTo())) {
            throw new ProblemException("m9.journal.period_invalid");
        }
        // An entity-level document takes the calendar date in the business time zone (CR-19A-8),
        // so a period is closed once that date has passed its end.
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        if (!command.periodTo().isBefore(today) && !command.provisional()) {
            throw new ProblemException(
                    "m9.journal.period_open", Map.of("to", command.periodTo().toString(), "today", today.toString()));
        }

        // One export at a time per entity: the second waits and then finds the postings taken.
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "m9.journal:" + scope.entityId());

        List<Posting> postings = new ArrayList<>();
        jdbc.query(
                """
                select p.posting_id, p.document_id, p.doc_type_code, p.doc_number_display, p.line_kind, p.side,
                       p.debit_role, p.credit_role, p.amount, p.business_date
                  from integration.journal_posting p
                 where p.business_date between ? and ?
                   and not exists (select 1 from integration.journal_line l where l.posting_id = p.posting_id)
                 order by p.business_date, p.doc_number_display, p.document_id, p.seq
                """,
                rs -> {
                    postings.add(new Posting(
                            rs.getObject("posting_id", UUID.class),
                            new JournalLineView(
                                    postings.size() + 1,
                                    rs.getObject("document_id", UUID.class),
                                    rs.getString("doc_type_code"),
                                    rs.getString("doc_number_display"),
                                    rs.getString("line_kind"),
                                    rs.getString("side"),
                                    rs.getString("debit_role"),
                                    rs.getString("credit_role"),
                                    rs.getBigDecimal("amount"),
                                    rs.getDate("business_date").toLocalDate(),
                                    null)));
                },
                Date.valueOf(command.periodFrom()),
                Date.valueOf(command.periodTo()));
        if (postings.isEmpty()) {
            throw new ProblemException(
                    "m9.journal.nothing_to_export",
                    Map.of(
                            "from",
                            command.periodFrom().toString(),
                            "to",
                            command.periodTo().toString()));
        }

        List<JournalLineView> lines = postings.stream().map(Posting::line).toList();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        Map<String, BigDecimal> byRole = new TreeMap<>();
        for (JournalLineView line : lines) {
            debit = debit.add(line.amount());
            credit = credit.add(line.amount());
            byRole.merge(line.debitRole(), line.amount(), BigDecimal::add);
            byRole.merge(line.creditRole(), line.amount().negate(), BigDecimal::add);
        }
        BigDecimal net = byRole.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debit.compareTo(credit) != 0 || net.signum() != 0) {
            throw new ProblemException("m9.journal.unbalanced");
        }

        // The file once: these bytes are what is stored, hashed, downloaded and reconciled.
        boolean provisional = command.provisional();
        byte[] file = JournalFiles.bytes(JournalFiles.CURRENT_VERSION, lines, provisional);
        String hash = JournalFiles.sha256(file);
        UUID exportId = Ids.next();
        Instant now = clock.instant();
        jdbc.update(
                """
                insert into integration.journal_export
                       (export_id, owner_entity_id, period_from, period_to, format, provisional, status, line_count,
                        total_debit, total_credit, content_hash, generated_at, requested_by, requested_at)
                values (?, ?, ?, ?, 'CSV', ?, 'GENERATED', ?, ?, ?, ?, ?, ?, ?)
                """,
                exportId,
                scope.entityId(),
                Date.valueOf(command.periodFrom()),
                Date.valueOf(command.periodTo()),
                provisional,
                lines.size(),
                debit,
                credit,
                hash,
                Timestamp.from(now),
                scope.userId(),
                Timestamp.from(now));
        jdbc.update(
                """
                insert into integration.journal_export_file
                       (export_id, format_version, content, content_hash, owner_entity_id)
                values (?, ?, ?, ?, ?)
                """,
                exportId,
                JournalFiles.CURRENT_VERSION,
                file,
                hash,
                scope.entityId());
        for (Posting posting : postings) {
            JournalLineView line = posting.line();
            jdbc.update(
                    """
                    insert into integration.journal_line
                           (line_id, export_id, seq, posting_id, document_id, doc_type_code, doc_number_display,
                            line_kind, side, debit_role, credit_role, amount, business_date, owner_entity_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    exportId,
                    line.seq(),
                    posting.postingId(),
                    line.documentId(),
                    line.docTypeCode(),
                    line.docNumberDisplay(),
                    line.lineKind(),
                    line.side(),
                    line.debitRole(),
                    line.creditRole(),
                    line.amount(),
                    Date.valueOf(line.businessDate()),
                    scope.entityId());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("periodFrom", command.periodFrom().toString());
        after.put("periodTo", command.periodTo().toString());
        after.put("format", "CSV");
        after.put("formatVersion", (int) JournalFiles.CURRENT_VERSION);
        after.put("provisional", provisional);
        after.put("lineCount", lines.size());
        after.put("totalDebit", debit);
        after.put("totalCredit", credit);
        after.put("contentHash", hash);
        audit.record(AUDIT_GENERATED, Subject.of("journal_export", exportId), null, after, scope);
        events.publish(new JournalExportGenerated(
                exportId, command.periodFrom(), command.periodTo(), lines.size(), debit, credit, hash, provisional));
        return exportId;
    }
}
