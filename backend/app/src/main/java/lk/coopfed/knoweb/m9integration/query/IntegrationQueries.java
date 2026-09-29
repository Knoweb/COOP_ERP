package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of M9 (29A section 4, IntegrationQueries): the journal exports of the caller's
 * entity, their lines and their reconciliation, what is waiting for the next export, and the
 * notification templates, rules and delivery log. Row-level security decides which rows the
 * caller sees; the methods never filter by entity themselves.
 */
public interface IntegrationQueries {

    /** The exports of the caller's scope, newest first (the run history). */
    List<JournalExportView> exports(ScopeContext scope);

    Optional<JournalExportView> export(UUID exportId, ScopeContext scope);

    /** The lines of an export in their order; empty when the caller may not see the export. */
    List<JournalLineView> lines(UUID exportId, ScopeContext scope);

    /** The journal file of an export for the accounting package (CSV), the bytes its content hash names. */
    Optional<String> file(UUID exportId, ScopeContext scope);

    /**
     * The reconciliation of an export: debits against credits, the totals by account role, and
     * whether the lines still give the totals and the content hash recorded at generation.
     */
    Optional<Reconciliation> reconciliation(UUID exportId, ScopeContext scope);

    /** What the next export over the period would take: postings no export has taken yet. */
    Pending pending(LocalDate periodFrom, LocalDate periodTo, ScopeContext scope);

    List<TemplateView> templates(ScopeContext scope);

    List<RuleView> rules(ScopeContext scope);

    /**
     * The kernel's delivery log of the caller's scope, newest first: status, attempts and the
     * reason of a suppression, the recipient as a hash only.
     *
     * @param status QUEUED, SENT, FAILED or SUPPRESSED; null for all
     */
    List<LogEntry> log(String status, int limit, ScopeContext scope);

    record JournalExportView(
            UUID exportId,
            LocalDate periodFrom,
            LocalDate periodTo,
            String format,
            String status,
            int lineCount,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            String contentHash,
            Instant generatedAt,
            UUID requestedBy) {}

    record JournalLineView(
            int seq,
            UUID documentId,
            String docTypeCode,
            String docNumberDisplay,
            String lineKind,
            String side,
            String debitRole,
            String creditRole,
            BigDecimal amount,
            LocalDate businessDate,
            String reference) {}

    record Reconciliation(
            UUID exportId,
            int lineCount,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            boolean balanced,
            boolean matchesRecordedTotals,
            boolean matchesRecordedHash,
            List<AccountTotal> accounts) {}

    /** One account role's side of an export: what it was debited and credited, and the net. */
    record AccountTotal(String role, BigDecimal debit, BigDecimal credit) {}

    record Pending(int postings, BigDecimal amount, LocalDate earliest, LocalDate latest) {}

    record TemplateView(
            String templateId,
            String channel,
            String subjectEn,
            String subjectSi,
            String subjectTa,
            String bodyEn,
            String bodySi,
            String bodyTa,
            List<String> placeholders,
            String status) {}

    record RuleView(
            UUID ruleId,
            String name,
            String eventType,
            String templateId,
            String audienceKind,
            String audienceRole,
            List<String> channels,
            String status,
            boolean federationWide) {}

    record LogEntry(
            UUID notificationId,
            Instant createdAt,
            UUID ruleId,
            UUID eventId,
            String channel,
            String templateId,
            String language,
            String status,
            int attempts,
            String suppressedReason,
            String lastError,
            String recipientHash) {}
}
