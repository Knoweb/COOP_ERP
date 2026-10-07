package lk.coopfed.knoweb.m9integration.internal.queries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalFileV1;
import lk.coopfed.knoweb.m9integration.internal.journal.JournalFiles;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of M9 over its own tables, and the delivery log over the kernel's
 * {@code kernel.notification_log}: the log is the kernel's (K-10), M9's screen shows it (29A
 * section 8, "Notification log"), and the kernel publishes no read of it, so it is read here,
 * select only, under the kernel's own policies (an entity its own rows, the Federation view all).
 */
@Service
@Transactional(readOnly = true)
class IntegrationQueriesImpl implements IntegrationQueries {

    static final Set<String> LOG_STATUSES = Set.of("QUEUED", "SENT", "FAILED", "SUPPRESSED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    IntegrationQueriesImpl(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String EXPORT_COLUMNS =
            "export_id, period_from, period_to, format, status, provisional, line_count, total_debit, total_credit,"
                    + " content_hash, generated_at, requested_by";

    private static final RowMapper<JournalExportView> EXPORT = (rs, i) -> new JournalExportView(
            rs.getObject("export_id", UUID.class),
            rs.getDate("period_from").toLocalDate(),
            rs.getDate("period_to").toLocalDate(),
            rs.getString("format"),
            rs.getString("status"),
            rs.getBoolean("provisional"),
            rs.getInt("line_count"),
            rs.getBigDecimal("total_debit"),
            rs.getBigDecimal("total_credit"),
            rs.getString("content_hash"),
            rs.getTimestamp("generated_at").toInstant(),
            rs.getObject("requested_by", UUID.class));

    private static final RowMapper<JournalLineView> LINE = (rs, i) -> new JournalLineView(
            rs.getInt("seq"),
            rs.getObject("document_id", UUID.class),
            rs.getString("doc_type_code"),
            rs.getString("doc_number_display"),
            rs.getString("line_kind"),
            rs.getString("side"),
            rs.getString("debit_role"),
            rs.getString("credit_role"),
            rs.getBigDecimal("amount"),
            rs.getDate("business_date").toLocalDate(),
            rs.getString("reference"));

    @Override
    public List<JournalExportView> exports(ScopeContext scope) {
        return jdbc.query(
                "select " + EXPORT_COLUMNS + " from integration.journal_export"
                        + " order by requested_at desc, export_id desc",
                EXPORT);
    }

    @Override
    public Optional<JournalExportView> export(UUID exportId, ScopeContext scope) {
        return jdbc
                .query(
                        "select " + EXPORT_COLUMNS + " from integration.journal_export where export_id = ?",
                        EXPORT,
                        exportId)
                .stream()
                .findFirst();
    }

    @Override
    public List<JournalLineView> lines(UUID exportId, ScopeContext scope) {
        return jdbc.query(
                """
                select seq, document_id, doc_type_code, doc_number_display, line_kind, side, debit_role, credit_role,
                       amount, business_date, reference
                  from integration.journal_line where export_id = ? order by seq
                """,
                LINE,
                exportId);
    }

    /** The stored file of an export: its writer's version and its bytes (wave 2, CR-29-1 item 2). */
    private record StoredFile(int formatVersion, byte[] content, String contentHash) {}

    private Optional<StoredFile> storedFile(UUID exportId) {
        return jdbc
                .query(
                        "select format_version, content, content_hash from integration.journal_export_file"
                                + " where export_id = ?",
                        (rs, i) -> new StoredFile(
                                rs.getInt("format_version"), rs.getBytes("content"), rs.getString("content_hash")),
                        exportId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> file(UUID exportId, ScopeContext scope) {
        Optional<JournalExportView> found = export(exportId, scope);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // The bytes stored at generation, byte for byte; an export from before V0006 has none and
        // is rebuilt by the frozen version-1 writer, which is what its hash was taken over.
        return Optional.of(storedFile(exportId)
                .map(stored -> new String(stored.content(), StandardCharsets.UTF_8))
                .orElseGet(() -> JournalFileV1.csv(lines(exportId, scope))));
    }

    @Override
    public SupplementDue supplementDue(ScopeContext scope) {
        return jdbc.queryForObject(
                """
                with exported as (select max(period_to) as up_to from integration.journal_export)
                select count(p.posting_id) as postings, coalesce(sum(p.amount), 0) as amount,
                       min(p.business_date) as earliest, max(p.business_date) as latest,
                       (select up_to from exported) as up_to
                  from integration.journal_posting p
                 where p.business_date <= (select up_to from exported)
                   and not exists (select 1 from integration.journal_line l where l.posting_id = p.posting_id)
                """,
                (rs, i) -> new SupplementDue(
                        rs.getInt("postings"),
                        rs.getBigDecimal("amount"),
                        date(rs, "earliest"),
                        date(rs, "latest"),
                        date(rs, "up_to")));
    }

    @Override
    public Optional<Reconciliation> reconciliation(UUID exportId, ScopeContext scope) {
        Optional<JournalExportView> found = export(exportId, scope);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        JournalExportView export = found.get();
        List<JournalLineView> lines = lines(exportId, scope);
        Map<String, BigDecimal[]> byRole = new TreeMap<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JournalLineView line : lines) {
            debit = debit.add(line.amount());
            credit = credit.add(line.amount());
            byRole.computeIfAbsent(line.debitRole(), r -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[0] =
                    byRole.get(line.debitRole())[0].add(line.amount());
            byRole.computeIfAbsent(line.creditRole(), r -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[1] =
                    byRole.get(line.creditRole())[1].add(line.amount());
        }
        List<AccountTotal> accounts = new ArrayList<>();
        byRole.forEach((role, sides) -> accounts.add(new AccountTotal(role, sides[0], sides[1])));
        BigDecimal roleDebits = accounts.stream().map(AccountTotal::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal roleCredits = accounts.stream().map(AccountTotal::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean balanced = debit.compareTo(credit) == 0 && roleDebits.compareTo(roleCredits) == 0;
        boolean totals = lines.size() == export.lineCount()
                && debit.compareTo(export.totalDebit()) == 0
                && credit.compareTo(export.totalCredit()) == 0;
        boolean hash = fileStillMatches(export, lines);
        return Optional.of(new Reconciliation(exportId, lines.size(), debit, credit, balanced, totals, hash, accounts));
    }

    /**
     * Whether the file still is the one generated (wave 2, CR-29-1 item 2): the stored bytes hash
     * to the recorded hash, and the writer of the recorded format version regenerates them from
     * the lines, so lines and file agree. An export from before V0006 has no stored file; for it
     * the frozen version-1 writer's file of the lines is hashed against the record.
     */
    private boolean fileStillMatches(JournalExportView export, List<JournalLineView> lines) {
        Optional<StoredFile> stored = storedFile(export.exportId());
        if (stored.isEmpty()) {
            return JournalFiles.sha256(JournalFileV1.csv(lines)).equals(export.contentHash());
        }
        StoredFile file = stored.get();
        byte[] regenerated = JournalFiles.bytes(file.formatVersion(), lines, export.provisional());
        return JournalFiles.sha256(file.content()).equals(export.contentHash())
                && file.contentHash().equals(export.contentHash())
                && Arrays.equals(regenerated, file.content());
    }

    @Override
    public Pending pending(LocalDate periodFrom, LocalDate periodTo, ScopeContext scope) {
        if (periodFrom == null || periodTo == null || periodFrom.isAfter(periodTo)) {
            throw new ProblemException("m9.journal.period_invalid");
        }
        return jdbc.queryForObject(
                """
                select count(*) as postings, coalesce(sum(p.amount), 0) as amount,
                       min(p.business_date) as earliest, max(p.business_date) as latest
                  from integration.journal_posting p
                 where p.business_date between ? and ?
                   and not exists (select 1 from integration.journal_line l where l.posting_id = p.posting_id)
                """,
                (rs, i) -> new Pending(
                        rs.getInt("postings"), rs.getBigDecimal("amount"), date(rs, "earliest"), date(rs, "latest")),
                Date.valueOf(periodFrom),
                Date.valueOf(periodTo));
    }

    @Override
    public List<TemplateView> templates(ScopeContext scope) {
        return jdbc.query(
                """
                select template_id, channel, subject_en, subject_si, subject_ta, body_en, body_si, body_ta,
                       placeholders_schema::text as placeholders, status
                  from integration.notification_template order by template_id
                """,
                (rs, i) -> new TemplateView(
                        rs.getString("template_id"),
                        rs.getString("channel"),
                        rs.getString("subject_en"),
                        rs.getString("subject_si"),
                        rs.getString("subject_ta"),
                        rs.getString("body_en"),
                        rs.getString("body_si"),
                        rs.getString("body_ta"),
                        placeholders(rs.getString("placeholders")),
                        rs.getString("status")));
    }

    @Override
    public List<RuleView> rules(ScopeContext scope) {
        return jdbc.query(
                """
                select rule_id, name, event_type, template_id, audience_kind,
                       coalesce(audience_spec ->> 'role', audience_spec ->> 'field',
                                audience_spec ->> 'from_payload') as spec,
                       channels, status, owner_entity_id is null as federation_wide
                  from integration.notification_rule order by event_type, name
                """,
                (rs, i) -> new RuleView(
                        rs.getObject("rule_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("event_type"),
                        rs.getString("template_id"),
                        rs.getString("audience_kind"),
                        rs.getString("spec"),
                        texts(rs, "channels"),
                        rs.getString("status"),
                        rs.getBoolean("federation_wide")));
    }

    @Override
    public List<LogEntry> log(String status, int limit, ScopeContext scope) {
        if (status != null && !LOG_STATUSES.contains(status)) {
            throw new ProblemException("m9.log.status_invalid", Map.of("status", status));
        }
        int rows = Math.max(1, Math.min(limit, 500));
        return jdbc.query(
                """
                select notification_id, created_at, rule_id, event_id, channel, template_id, language, status,
                       attempts, suppressed_reason, last_error, recipient_entity_id, audience_role,
                       next_attempt_at,
                       case when recipient_hash_key_id is not null then left(recipient_hash, 8) end as recipient_tag
                  from kernel.notification_log
                 where (?::text is null or status = ?::text)
                 order by created_at desc, notification_id desc
                 limit ?
                """,
                (rs, i) -> new LogEntry(
                        rs.getObject("notification_id", UUID.class),
                        instant(rs.getTimestamp("created_at")),
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("template_id"),
                        rs.getString("language") == null
                                ? null
                                : rs.getString("language").strip(),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("suppressed_reason"),
                        rs.getString("last_error"),
                        rs.getObject("recipient_entity_id", UUID.class),
                        rs.getString("audience_role"),
                        // Eight characters of the keyed hash tell two numbers of one role apart and
                        // give nothing back without the key; a row whose hash V0085 replaced has none.
                        rs.getString("recipient_tag"),
                        instant(rs.getTimestamp("next_attempt_at"))),
                status,
                status,
                rows);
    }

    private List<String> placeholders(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static List<String> texts(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
