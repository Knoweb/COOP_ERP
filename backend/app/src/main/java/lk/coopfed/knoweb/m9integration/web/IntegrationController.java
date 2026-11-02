package lk.coopfed.knoweb.m9integration.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.ApproveOvertime;
import lk.coopfed.knoweb.m9integration.api.AttendanceImportResult;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords;
import lk.coopfed.knoweb.m9integration.api.RegisterEmployee;
import lk.coopfed.knoweb.m9integration.api.RequestJournalExport;
import lk.coopfed.knoweb.m9integration.api.SetNotificationRuleStatus;
import lk.coopfed.knoweb.m9integration.query.ApprovedPayrollContributionQueries;
import lk.coopfed.knoweb.m9integration.query.AttendanceQueries;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalExportView;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.RuleView;
import lk.coopfed.knoweb.m9integration.web.generated.AccountTotalResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ApproveOvertimeRequest;
import lk.coopfed.knoweb.m9integration.web.generated.AttendanceImportResponse;
import lk.coopfed.knoweb.m9integration.web.generated.AttendanceRecordResponse;
import lk.coopfed.knoweb.m9integration.web.generated.AttendanceRejection;
import lk.coopfed.knoweb.m9integration.web.generated.EmployeeResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ImportAttendanceRequest;
import lk.coopfed.knoweb.m9integration.web.generated.IntegrationApi;
import lk.coopfed.knoweb.m9integration.web.generated.JournalExportResponse;
import lk.coopfed.knoweb.m9integration.web.generated.JournalLineResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationLogResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationRuleResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationTemplateResponse;
import lk.coopfed.knoweb.m9integration.web.generated.OvertimeCalculationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PayrollContributionResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PayrollContributionSummaryResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PendingPostingsResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ReconciliationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.RegisterEmployeeRequest;
import lk.coopfed.knoweb.m9integration.web.generated.RequestJournalExportRequest;
import lk.coopfed.knoweb.m9integration.web.generated.SupplementDueResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of M9: the journal exports, notification screens, biometric attendance and
 * overtime integration with payroll.
 */
@RestController
class IntegrationController implements IntegrationApi {

    /** The number of log rows when the caller does not say. */
    private static final int LOG_ROWS = 100;

    private final IntegrationQueries queries;
    private final EmployeeQueries employeeQueries;
    private final AttendanceQueries attendanceQueries;
    private final ApprovedPayrollContributionQueries payrollQueries;
    private final CurrentScope currentScope;
    private final Handles<RequestJournalExport, UUID> requestExport;
    private final Handles<SetNotificationRuleStatus, UUID> setRuleStatus;
    private final Handles<RegisterEmployee, UUID> registerEmployee;
    private final Handles<ImportAttendanceRecords, AttendanceImportResult> importAttendance;
    private final Handles<ApproveOvertime, UUID> approveOvertime;

    IntegrationController(
            IntegrationQueries queries,
            EmployeeQueries employeeQueries,
            AttendanceQueries attendanceQueries,
            ApprovedPayrollContributionQueries payrollQueries,
            CurrentScope currentScope,
            Handles<RequestJournalExport, UUID> requestExport,
            Handles<SetNotificationRuleStatus, UUID> setRuleStatus,
            Handles<RegisterEmployee, UUID> registerEmployee,
            Handles<ImportAttendanceRecords, AttendanceImportResult> importAttendance,
            Handles<ApproveOvertime, UUID> approveOvertime) {
        this.queries = queries;
        this.employeeQueries = employeeQueries;
        this.attendanceQueries = attendanceQueries;
        this.payrollQueries = payrollQueries;
        this.currentScope = currentScope;
        this.requestExport = requestExport;
        this.setRuleStatus = setRuleStatus;
        this.registerEmployee = registerEmployee;
        this.importAttendance = importAttendance;
        this.approveOvertime = approveOvertime;
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

    // --- Employee Endpoints ---

    @Override
    public ResponseEntity<List<EmployeeResponse>> listEmployees() {
        return ResponseEntity.ok(employeeQueries.listEmployees(currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<EmployeeResponse> registerEmployee(String idempotencyKey, RegisterEmployeeRequest request) {
        ScopeContext scope = currentScope.get();
        UUID employeeId = registerEmployee.handle(
                new RegisterEmployee(
                        request.getEmployeeNo(),
                        request.getFullName(),
                        request.getNic(),
                        request.getEpfNo(),
                        request.getHourlyRate(),
                        request.getLocationId()),
                scope);
        EmployeeQueries.EmployeeView employee =
                employeeQueries.getEmployee(employeeId, scope).orElseThrow();
        return ResponseEntity.created(URI.create(IntegrationApi.PATH_LIST_EMPLOYEES + "/" + employeeId))
                .body(toResponse(employee));
    }

    @Override
    public ResponseEntity<EmployeeResponse> getEmployee(UUID employeeId) {
        return employeeQueries
                .getEmployee(employeeId, currentScope.get())
                .map(IntegrationController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // --- Attendance Endpoints ---

    @Override
    public ResponseEntity<AttendanceImportResponse> importAttendance(
            String idempotencyKey, ImportAttendanceRequest request) {
        ScopeContext scope = currentScope.get();
        List<ImportAttendanceRecords.AttendanceImportItem> items = request.getItems() == null
                ? List.of()
                : request.getItems().stream()
                        .map(i -> new ImportAttendanceRecords.AttendanceImportItem(
                                i.getExternalRecordId(),
                                i.getEmployeeNo(),
                                i.getDeviceIdentifier(),
                                i.getSourceProvider(),
                                i.getBusinessDate(),
                                i.getClockIn(),
                                i.getClockOut(),
                                i.getRawPayload()))
                        .toList();
        AttendanceImportResult result = importAttendance.handle(new ImportAttendanceRecords(items), scope);
        return ResponseEntity.ok(new AttendanceImportResponse(
                result.imported(),
                result.duplicate(),
                result.rejected(),
                result.recordIds(),
                result.rejections().stream()
                        .map(r -> new AttendanceRejection(r.externalRecordId(), r.reason()))
                        .toList()));
    }

    @Override
    public ResponseEntity<List<AttendanceRecordResponse>> listAttendance(LocalDate businessDate, UUID employeeId) {
        return ResponseEntity.ok(attendanceQueries.listAttendance(businessDate, employeeId, currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    // --- Overtime Endpoints ---

    @Override
    public ResponseEntity<List<OvertimeCalculationResponse>> listOvertimeCalculations(
            String status, LocalDate businessDate) {
        return ResponseEntity.ok(attendanceQueries.listOvertime(status, businessDate, currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<PayrollContributionResponse> approveOvertime(
            UUID calculationId, String idempotencyKey, ApproveOvertimeRequest request) {
        ScopeContext scope = currentScope.get();
        UUID contributionId = approveOvertime.handle(
                new ApproveOvertime(calculationId, request.getPayrollPeriod(), request.getNotes()), scope);
        ApprovedPayrollContributionQueries.ApprovedPayrollContributionView view =
                payrollQueries.getContribution(contributionId, scope).orElseThrow();
        return ResponseEntity.ok(toResponse(view));
    }

    // --- Payroll Contribution Endpoints ---

    @Override
    public ResponseEntity<List<PayrollContributionResponse>> listPayrollContributions(String payrollPeriod) {
        return ResponseEntity.ok(payrollQueries.listApprovedContributions(payrollPeriod, currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<PayrollContributionSummaryResponse> getPayrollContributionSummary(String payrollPeriod) {
        ApprovedPayrollContributionQueries.PayrollContributionSummary summary =
                payrollQueries.getPeriodSummary(payrollPeriod, currentScope.get());
        return ResponseEntity.ok(new PayrollContributionSummaryResponse(
                summary.payrollPeriod(),
                summary.totalEmployees(),
                summary.totalOtMinutes(),
                summary.totalApprovedAmount(),
                summary.contributionCount()));
    }

    // --- Helpers ---

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

    private static EmployeeResponse toResponse(EmployeeQueries.EmployeeView e) {
        return new EmployeeResponse(
                        e.employeeId(),
                        e.ownerEntityId(),
                        e.employeeNo(),
                        e.fullName(),
                        e.hourlyRate(),
                        EmployeeResponse.StatusEnum.fromValue(e.status()),
                        e.createdAt(),
                        e.updatedAt())
                .nic(e.nic())
                .epfNo(e.epfNo())
                .locationId(e.locationId());
    }

    private static AttendanceRecordResponse toResponse(AttendanceQueries.AttendanceRecordView a) {
        return new AttendanceRecordResponse(
                        a.attendanceId(),
                        a.ownerEntityId(),
                        a.externalRecordId(),
                        a.employeeId(),
                        a.employeeNo(),
                        a.employeeName(),
                        a.deviceIdentifier(),
                        a.sourceProvider(),
                        a.businessDate(),
                        a.clockIn(),
                        a.clockOut(),
                        a.workedMinutes(),
                        AttendanceRecordResponse.StatusEnum.fromValue(a.status()),
                        a.createdAt())
                .rejectionReason(a.rejectionReason());
    }

    private static OvertimeCalculationResponse toResponse(AttendanceQueries.OvertimeCalculationView o) {
        return new OvertimeCalculationResponse(
                o.calculationId(),
                o.attendanceId(),
                o.ownerEntityId(),
                o.employeeId(),
                o.employeeNo(),
                o.employeeName(),
                o.businessDate(),
                o.workedMinutes(),
                o.standardMinutes(),
                o.qualifyingOtMinutes(),
                o.calculatedOtMinutes(),
                o.otMultiplier(),
                o.calculatedAmount(),
                OvertimeCalculationResponse.StatusEnum.fromValue(o.status()),
                o.createdAt());
    }

    private static PayrollContributionResponse toResponse(
            ApprovedPayrollContributionQueries.ApprovedPayrollContributionView p) {
        return new PayrollContributionResponse(
                        p.contributionId(),
                        p.ownerEntityId(),
                        p.employeeId(),
                        p.employeeNo(),
                        p.employeeName(),
                        p.calculationId(),
                        p.payrollPeriod(),
                        PayrollContributionResponse.ContributionTypeEnum.fromValue(p.contributionType()),
                        p.approvedOtMinutes(),
                        p.approvedAmount(),
                        p.approvedBy(),
                        p.approvedAt(),
                        PayrollContributionResponse.StatusEnum.fromValue(p.status()),
                        p.createdAt())
                .approvalNotes(p.approvalNotes());
    }

    static String fileName(JournalExportView export) {
        return "journal_" + export.periodFrom() + "_" + export.periodTo() + (export.provisional() ? "_PROVISIONAL" : "")
                + "_" + export.exportId().toString().substring(24) + ".csv";
    }
}
