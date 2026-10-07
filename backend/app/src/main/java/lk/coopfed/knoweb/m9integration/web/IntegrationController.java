package lk.coopfed.knoweb.m9integration.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.RequestJournalExport;
import lk.coopfed.knoweb.m9integration.api.SetNotificationRuleStatus;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalExportView;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.RuleView;
import lk.coopfed.knoweb.m9integration.web.generated.AccountTotalResponse;
import lk.coopfed.knoweb.m9integration.web.generated.IntegrationApi;
import lk.coopfed.knoweb.m9integration.web.generated.JournalExportResponse;
import lk.coopfed.knoweb.m9integration.web.generated.JournalLineResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationLogResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationRuleResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationTemplateResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PendingPostingsResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ReconciliationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.RequestJournalExportRequest;
import lk.coopfed.knoweb.m9integration.web.generated.SupplementDueResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of M9 for the demo (29A section 5): the journal exports and the notification
 * screens. It translates only: request to command, view to response. The kernel checks each
 * operation's x-permission and idempotency key; row-level security decides which rows the caller
 * sees, and an export the caller may not see is a 404, like one that does not exist.
 */
@RestController
class IntegrationController implements IntegrationApi {

    /** The number of log rows when the caller does not say. */
    private static final int LOG_ROWS = 100;

    private final IntegrationQueries queries;
    private final CurrentScope currentScope;
    private final Handles<RequestJournalExport, UUID> requestExport;
    private final Handles<SetNotificationRuleStatus, UUID> setRuleStatus;

    IntegrationController(
            IntegrationQueries queries,
            CurrentScope currentScope,
            Handles<RequestJournalExport, UUID> requestExport,
            Handles<SetNotificationRuleStatus, UUID> setRuleStatus) {
        this.queries = queries;
        this.currentScope = currentScope;
        this.requestExport = requestExport;
        this.setRuleStatus = setRuleStatus;
    }

    @Override
    public ResponseEntity<List<JournalExportResponse>> listJournalExports() {
        return ResponseEntity.ok(queries.exports(currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<JournalExportResponse> requestJournalExport(
            String idempotencyKey, RequestJournalExportRequest request) {
        ScopeContext scope = currentScope.get();
        UUID exportId = requestExport.handle(
                new RequestJournalExport(
                        request.getPeriodFrom(), request.getPeriodTo(), Boolean.TRUE.equals(request.getProvisional())),
                scope);
        JournalExportView export = queries.export(exportId, scope).orElseThrow();
        return ResponseEntity.created(URI.create(IntegrationApi.PATH_LIST_JOURNAL_EXPORTS + "/" + exportId))
                .body(toResponse(export));
    }

    @Override
    public ResponseEntity<JournalExportResponse> getJournalExport(UUID exportId) {
        return queries.export(exportId, currentScope.get())
                .map(IntegrationController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<List<JournalLineResponse>> listJournalLines(UUID exportId) {
        ScopeContext scope = currentScope.get();
        if (queries.export(exportId, scope).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(queries.lines(exportId, scope).stream()
                .map(line -> new JournalLineResponse(
                                line.seq(),
                                line.documentId(),
                                line.docTypeCode(),
                                line.docNumberDisplay(),
                                line.lineKind(),
                                JournalLineResponse.SideEnum.fromValue(line.side()),
                                line.debitRole(),
                                line.creditRole(),
                                line.amount(),
                                line.businessDate())
                        .reference(line.reference()))
                .toList());
    }

    @Override
    public ResponseEntity<String> downloadJournalExport(UUID exportId) {
        ScopeContext scope = currentScope.get();
        return queries.export(exportId, scope)
                .flatMap(export -> queries.file(exportId, scope).map(file -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment()
                                        .filename(fileName(export))
                                        .build()
                                        .toString())
                        .body(file)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<ReconciliationResponse> getJournalReconciliation(UUID exportId) {
        return queries.reconciliation(exportId, currentScope.get())
                .map(r -> new ReconciliationResponse(
                        r.exportId(),
                        r.lineCount(),
                        r.totalDebit(),
                        r.totalCredit(),
                        r.balanced(),
                        r.matchesRecordedTotals(),
                        r.matchesRecordedHash(),
                        r.accounts().stream()
                                .map(a -> new AccountTotalResponse(a.role(), a.debit(), a.credit()))
                                .toList()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<PendingPostingsResponse> getPendingPostings(LocalDate from, LocalDate to) {
        IntegrationQueries.Pending pending = queries.pending(from, to, currentScope.get());
        return ResponseEntity.ok(new PendingPostingsResponse(pending.postings(), pending.amount())
                .earliest(pending.earliest())
                .latest(pending.latest()));
    }

    @Override
    public ResponseEntity<SupplementDueResponse> getSupplementDue() {
        IntegrationQueries.SupplementDue due = queries.supplementDue(currentScope.get());
        return ResponseEntity.ok(new SupplementDueResponse(due.postings(), due.amount())
                .earliest(due.earliest())
                .latest(due.latest())
                .upTo(due.upTo()));
    }

    @Override
    public ResponseEntity<List<NotificationTemplateResponse>> listNotificationTemplates() {
        return ResponseEntity.ok(queries.templates(currentScope.get()).stream()
                .map(t -> new NotificationTemplateResponse(
                                t.templateId(),
                                NotificationTemplateResponse.ChannelEnum.fromValue(t.channel()),
                                t.bodyEn(),
                                t.placeholders(),
                                NotificationTemplateResponse.StatusEnum.fromValue(t.status()))
                        .subjectEn(t.subjectEn())
                        .subjectSi(t.subjectSi())
                        .subjectTa(t.subjectTa())
                        .bodySi(t.bodySi())
                        .bodyTa(t.bodyTa()))
                .toList());
    }

    @Override
    public ResponseEntity<List<NotificationRuleResponse>> listNotificationRules() {
        return ResponseEntity.ok(queries.rules(currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<NotificationRuleResponse> activateNotificationRule(String idempotencyKey, UUID ruleId) {
        return setStatus(ruleId, SetNotificationRuleStatus.ACTIVE);
    }

    @Override
    public ResponseEntity<NotificationRuleResponse> retireNotificationRule(String idempotencyKey, UUID ruleId) {
        return setStatus(ruleId, SetNotificationRuleStatus.RETIRED);
    }

    @Override
    public ResponseEntity<List<NotificationLogResponse>> listNotificationLog(String status, Integer limit) {
        return ResponseEntity.ok(queries.log(status, limit == null ? LOG_ROWS : limit, currentScope.get()).stream()
                .map(e -> new NotificationLogResponse(
                                e.notificationId(),
                                e.createdAt(),
                                NotificationLogResponse.ChannelEnum.fromValue(e.channel()),
                                NotificationLogResponse.StatusEnum.fromValue(e.status()),
                                e.attempts())
                        .ruleId(e.ruleId())
                        .eventId(e.eventId())
                        .templateId(e.templateId())
                        .language(e.language())
                        .suppressedReason(e.suppressedReason())
                        .lastError(e.lastError())
                        .recipientEntityId(e.recipientEntityId())
                        .audienceRole(e.audienceRole())
                        .recipientTag(e.recipientTag())
                        .nextAttemptAt(e.nextAttemptAt()))
                .toList());
    }

    private ResponseEntity<NotificationRuleResponse> setStatus(UUID ruleId, String status) {
        ScopeContext scope = currentScope.get();
        setRuleStatus.handle(new SetNotificationRuleStatus(ruleId, status), scope);
        return queries.rules(scope).stream()
                .filter(rule -> rule.ruleId().equals(ruleId))
                .findFirst()
                .map(IntegrationController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static NotificationRuleResponse toResponse(RuleView rule) {
        return new NotificationRuleResponse(
                        rule.ruleId(),
                        rule.name(),
                        rule.eventType(),
                        rule.templateId(),
                        NotificationRuleResponse.AudienceKindEnum.fromValue(rule.audienceKind()),
                        rule.channels(),
                        NotificationRuleResponse.StatusEnum.fromValue(rule.status()),
                        rule.federationWide())
                .audienceRole(rule.audienceRole());
    }

    private static JournalExportResponse toResponse(JournalExportView export) {
        return new JournalExportResponse(
                export.exportId(),
                export.periodFrom(),
                export.periodTo(),
                JournalExportResponse.FormatEnum.fromValue(export.format()),
                JournalExportResponse.StatusEnum.fromValue(export.status()),
                export.provisional(),
                export.lineCount(),
                export.totalDebit(),
                export.totalCredit(),
                export.contentHash(),
                export.generatedAt());
    }

    /**
     * journal_2026-09-01_2026-09-30_8000000000a1.csv: the period and the end of the export id (its
     * random part); journal_2026-09-01_2026-09-30_PROVISIONAL_8000000000a1.csv for a provisional
     * export (wave 2, CR-29-1 item 1), so the accountant sees the mark in the folder too.
     */
    static String fileName(JournalExportView export) {
        return "journal_" + export.periodFrom() + "_" + export.periodTo() + (export.provisional() ? "_PROVISIONAL" : "")
                + "_" + export.exportId().toString().substring(24) + ".csv";
    }
}
