package lk.coopfed.knoweb.m9integration.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.AcknowledgeJournalExport;
import lk.coopfed.knoweb.m9integration.api.ApproveOvertime;
import lk.coopfed.knoweb.m9integration.api.AttendanceImportResult;
import lk.coopfed.knoweb.m9integration.api.CompleteDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.ConfirmCodSettlement;
import lk.coopfed.knoweb.m9integration.api.ConfirmPayment;
import lk.coopfed.knoweb.m9integration.api.CreateDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords;
import lk.coopfed.knoweb.m9integration.api.InitiatePayment;
import lk.coopfed.knoweb.m9integration.api.NotificationGateway;
import lk.coopfed.knoweb.m9integration.api.NotificationResult;
import lk.coopfed.knoweb.m9integration.api.RecordDeliveryDrop;
import lk.coopfed.knoweb.m9integration.api.RecordVehicleLocation;
import lk.coopfed.knoweb.m9integration.api.RegisterEmployee;
import lk.coopfed.knoweb.m9integration.api.RequestJournalExport;
import lk.coopfed.knoweb.m9integration.api.RetryPayment;
import lk.coopfed.knoweb.m9integration.api.SetNotificationRuleStatus;
import lk.coopfed.knoweb.m9integration.api.UpdateDeliveryStatus;
import lk.coopfed.knoweb.m9integration.query.ApprovedPayrollContributionQueries;
import lk.coopfed.knoweb.m9integration.query.AttendanceQueries;
import lk.coopfed.knoweb.m9integration.query.DailyDeliverySummaryView;
import lk.coopfed.knoweb.m9integration.query.DeliveryDropView;
import lk.coopfed.knoweb.m9integration.query.DeliveryTrackingQueries;
import lk.coopfed.knoweb.m9integration.query.DeliveryTripSummaryView;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalExportView;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.RuleView;
import lk.coopfed.knoweb.m9integration.query.LiveVehicleLocationView;
import lk.coopfed.knoweb.m9integration.query.MonthlyDeliveryReportView;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentQueries;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import lk.coopfed.knoweb.m9integration.query.VehicleRoutePointView;
import lk.coopfed.knoweb.m9integration.web.generated.AccountTotalResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ApproveOvertimeRequest;
import lk.coopfed.knoweb.m9integration.web.generated.AttendanceImportResponse;
import lk.coopfed.knoweb.m9integration.web.generated.AttendanceRecordResponse;
import lk.coopfed.knoweb.m9integration.web.generated.AttendanceRejection;
import lk.coopfed.knoweb.m9integration.web.generated.CompleteDeliveryTripRequest;
import lk.coopfed.knoweb.m9integration.web.generated.ConfirmCodPaymentRequest;
import lk.coopfed.knoweb.m9integration.web.generated.ConfirmPaymentRequest;
import lk.coopfed.knoweb.m9integration.web.generated.CreateDeliveryTripRequest;
import lk.coopfed.knoweb.m9integration.web.generated.DailyDeliveryBreakdownItem;
import lk.coopfed.knoweb.m9integration.web.generated.DailyDeliverySummaryResponse;
import lk.coopfed.knoweb.m9integration.web.generated.DeliveryDropResponse;
import lk.coopfed.knoweb.m9integration.web.generated.DeliveryTripResponse;
import lk.coopfed.knoweb.m9integration.web.generated.EmployeeResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ImportAttendanceRequest;
import lk.coopfed.knoweb.m9integration.web.generated.InitiatePaymentRequest;
import lk.coopfed.knoweb.m9integration.web.generated.IntegrationApi;
import lk.coopfed.knoweb.m9integration.web.generated.JournalExportResponse;
import lk.coopfed.knoweb.m9integration.web.generated.JournalLineResponse;
import lk.coopfed.knoweb.m9integration.web.generated.LiveVehicleLocationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.MonthlyDeliveryReportResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationLogResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationResultResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationRuleResponse;
import lk.coopfed.knoweb.m9integration.web.generated.NotificationTemplateResponse;
import lk.coopfed.knoweb.m9integration.web.generated.OvertimeCalculationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PaymentIntentResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PayrollContributionResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PayrollContributionSummaryResponse;
import lk.coopfed.knoweb.m9integration.web.generated.PendingPostingsResponse;
import lk.coopfed.knoweb.m9integration.web.generated.ReconciliationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.RecordDeliveryDropRequest;
import lk.coopfed.knoweb.m9integration.web.generated.RecordVehicleLocationRequest;
import lk.coopfed.knoweb.m9integration.web.generated.RegisterEmployeeRequest;
import lk.coopfed.knoweb.m9integration.web.generated.RequestJournalExportRequest;
import lk.coopfed.knoweb.m9integration.web.generated.RetryPaymentRequest;
import lk.coopfed.knoweb.m9integration.web.generated.SupplementDueResponse;
import lk.coopfed.knoweb.m9integration.web.generated.UpdateDeliveryStatusRequest;
import lk.coopfed.knoweb.m9integration.web.generated.VehicleDeliveryBreakdownItem;
import lk.coopfed.knoweb.m9integration.web.generated.VehicleLocationResponse;
import lk.coopfed.knoweb.m9integration.web.generated.VehicleRouteHistoryResponse;
import lk.coopfed.knoweb.m9integration.web.generated.VehicleRoutePointResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of M9: the journal exports, notification screens, biometric
 * attendance and
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
    private final DeliveryTrackingQueries deliveryQueries;
    private final Clock clock;
    private final CurrentScope currentScope;
    private final Handles<RequestJournalExport, UUID> requestExport;
    private final Handles<AcknowledgeJournalExport, UUID> acknowledgeExport;
    private final Handles<SetNotificationRuleStatus, UUID> setRuleStatus;
    private final Handles<RegisterEmployee, UUID> registerEmployee;
    private final Handles<ImportAttendanceRecords, AttendanceImportResult> importAttendance;
    private final Handles<ApproveOvertime, UUID> approveOvertime;
    private final Handles<RecordVehicleLocation, UUID> recordVehicleLocation;
    private final Handles<CreateDeliveryTrip, UUID> createDeliveryTrip;
    private final Handles<CompleteDeliveryTrip, Void> completeDeliveryTrip;
    private final Handles<RecordDeliveryDrop, UUID> recordDeliveryDrop;
    private final Handles<UpdateDeliveryStatus, Void> updateDeliveryStatus;
    private final PaymentIntentQueries paymentQueries;
    private final Handles<InitiatePayment, PaymentIntentView> initiatePayment;
    private final Handles<ConfirmPayment, PaymentIntentView> confirmPayment;
    private final Handles<ConfirmCodSettlement, PaymentIntentView> confirmCodPayment;
    private final Handles<RetryPayment, PaymentIntentView> retryPayment;
    private final NotificationGateway notificationGateway;

    IntegrationController(
            IntegrationQueries queries,
            EmployeeQueries employeeQueries,
            AttendanceQueries attendanceQueries,
            ApprovedPayrollContributionQueries payrollQueries,
            DeliveryTrackingQueries deliveryQueries,
            Clock clock,
            CurrentScope currentScope,
            Handles<RequestJournalExport, UUID> requestExport,
            Handles<AcknowledgeJournalExport, UUID> acknowledgeExport,
            Handles<SetNotificationRuleStatus, UUID> setRuleStatus,
            Handles<RegisterEmployee, UUID> registerEmployee,
            Handles<ImportAttendanceRecords, AttendanceImportResult> importAttendance,
            Handles<ApproveOvertime, UUID> approveOvertime,
            Handles<RecordVehicleLocation, UUID> recordVehicleLocation,
            Handles<CreateDeliveryTrip, UUID> createDeliveryTrip,
            Handles<CompleteDeliveryTrip, Void> completeDeliveryTrip,
            Handles<RecordDeliveryDrop, UUID> recordDeliveryDrop,
            Handles<UpdateDeliveryStatus, Void> updateDeliveryStatus,
            PaymentIntentQueries paymentQueries,
            Handles<InitiatePayment, PaymentIntentView> initiatePayment,
            Handles<ConfirmPayment, PaymentIntentView> confirmPayment,
            Handles<ConfirmCodSettlement, PaymentIntentView> confirmCodPayment,
            Handles<RetryPayment, PaymentIntentView> retryPayment,
            NotificationGateway notificationGateway) {
        this.queries = queries;
        this.employeeQueries = employeeQueries;
        this.attendanceQueries = attendanceQueries;
        this.payrollQueries = payrollQueries;
        this.deliveryQueries = deliveryQueries;
        this.clock = clock;
        this.currentScope = currentScope;
        this.requestExport = requestExport;
        this.acknowledgeExport = acknowledgeExport;
        this.setRuleStatus = setRuleStatus;
        this.registerEmployee = registerEmployee;
        this.importAttendance = importAttendance;
        this.approveOvertime = approveOvertime;
        this.recordVehicleLocation = recordVehicleLocation;
        this.createDeliveryTrip = createDeliveryTrip;
        this.completeDeliveryTrip = completeDeliveryTrip;
        this.recordDeliveryDrop = recordDeliveryDrop;
        this.updateDeliveryStatus = updateDeliveryStatus;
        this.paymentQueries = paymentQueries;
        this.initiatePayment = initiatePayment;
        this.confirmPayment = confirmPayment;
        this.confirmCodPayment = confirmCodPayment;
        this.retryPayment = retryPayment;
        this.notificationGateway = notificationGateway;
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
    public ResponseEntity<JournalExportResponse> acknowledgeJournalExport(UUID exportId, String idempotencyKey) {
        ScopeContext scope = currentScope.get();
        UUID acknowledgedId = acknowledgeExport.handle(new AcknowledgeJournalExport(exportId), scope);
        JournalExportView export = queries.export(acknowledgedId, scope).orElseThrow();
        return ResponseEntity.ok(toResponse(export));
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

    @Override
    public ResponseEntity<NotificationResultResponse> retryNotification(String idempotencyKey, UUID notificationId) {
        ScopeContext scope = currentScope.get();
        NotificationResult res = notificationGateway.retry(notificationId, scope);
        NotificationResultResponse resp = new NotificationResultResponse(
                        res.requestId(), NotificationResultResponse.StatusEnum.fromValue(res.status()))
                .providerRef(res.providerRef())
                .error(res.error())
                .duplicate(res.duplicate());
        return ResponseEntity.ok(resp);
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

    // --- Delivery Tracking Endpoints ---

    @Override
    public ResponseEntity<VehicleLocationResponse> recordVehicleLocation(
            String idempotencyKey, RecordVehicleLocationRequest request) {
        ScopeContext scope = currentScope.get();
        UUID updateId = recordVehicleLocation.handle(
                new RecordVehicleLocation(
                        request.getVehicleId(),
                        request.getDeviceId(),
                        request.getTripId(),
                        request.getLatitude(),
                        request.getLongitude(),
                        request.getSpeedKmh(),
                        request.getHeadingDegrees(),
                        request.getBatteryPercentage(),
                        request.getRecordedAt()),
                scope);
        VehicleLocationResponse response = new VehicleLocationResponse(
                        updateId,
                        request.getVehicleId(),
                        request.getDeviceId(),
                        request.getLatitude(),
                        request.getLongitude(),
                        request.getSpeedKmh(),
                        request.getRecordedAt(),
                        clock.instant())
                .headingDegrees(request.getHeadingDegrees())
                .batteryPercentage(request.getBatteryPercentage());
        return ResponseEntity.created(URI.create(IntegrationApi.PATH_RECORD_VEHICLE_LOCATION + "/" + updateId))
                .body(response);
    }

    @Override
    public ResponseEntity<List<LiveVehicleLocationResponse>> listLiveVehicleLocations() {
        return ResponseEntity.ok(deliveryQueries.listLiveVehicleLocations(currentScope.get()).stream()
                .map(IntegrationController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<LiveVehicleLocationResponse> getLiveVehicleLocation(String vehicleId) {
        return deliveryQueries
                .getLatestVehicleLocation(vehicleId, currentScope.get())
                .map(IntegrationController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<VehicleRouteHistoryResponse> getVehicleRouteHistory(String vehicleId, LocalDate date) {
        List<VehicleRoutePointView> points =
                deliveryQueries.getVehicleRouteHistory(vehicleId, date, currentScope.get());
        List<VehicleRoutePointResponse> pointResponses = points.stream()
                .map(p -> new VehicleRoutePointResponse(p.recordedAt(), p.latitude(), p.longitude(), p.speedKmh())
                        .headingDegrees(p.headingDegrees()))
                .toList();
        return ResponseEntity.ok(
                new VehicleRouteHistoryResponse(vehicleId, date, pointResponses.size(), pointResponses));
    }

    @Override
    public ResponseEntity<DeliveryTripResponse> createDeliveryTrip(
            String idempotencyKey, CreateDeliveryTripRequest request) {
        ScopeContext scope = currentScope.get();
        UUID tripId = createDeliveryTrip.handle(
                new CreateDeliveryTrip(
                        request.getTripReference(),
                        request.getVehicleId(),
                        request.getDriverUserId(),
                        request.getDriverCode(),
                        request.getOriginLocationId(),
                        request.getDestinationLocationId(),
                        request.getTripDate(),
                        request.getStartOdometerKm()),
                scope);
        DeliveryTripSummaryView trip =
                deliveryQueries.getDeliveryTrip(tripId, scope).orElseThrow();
        return ResponseEntity.created(URI.create(IntegrationApi.PATH_CREATE_DELIVERY_TRIP + "/" + tripId))
                .body(toResponse(trip));
    }

    @Override
    public ResponseEntity<DeliveryTripResponse> completeDeliveryTrip(
            UUID tripId, String idempotencyKey, CompleteDeliveryTripRequest request) {
        ScopeContext scope = currentScope.get();
        completeDeliveryTrip.handle(
                new CompleteDeliveryTrip(
                        tripId,
                        request != null ? request.getEndOdometerKm() : null,
                        request != null ? request.getTotalDistanceKm() : null),
                scope);
        DeliveryTripSummaryView trip =
                deliveryQueries.getDeliveryTrip(tripId, scope).orElseThrow();
        return ResponseEntity.ok(toResponse(trip));
    }

    @Override
    public ResponseEntity<DeliveryDropResponse> recordDeliveryDrop(
            UUID tripId, String idempotencyKey, RecordDeliveryDropRequest request) {
        ScopeContext scope = currentScope.get();
        UUID deliveryId = recordDeliveryDrop.handle(
                new RecordDeliveryDrop(
                        tripId,
                        request.getDeliveryReference(),
                        request.getDestinationLocationId(),
                        request.getRecipientRef(),
                        request.getPackagesCount(),
                        request.getWeightKg(),
                        request.getScheduledAt(),
                        request.getNotes()),
                scope);
        DeliveryDropView drop =
                deliveryQueries.getDeliveryDrop(deliveryId, scope).orElseThrow();
        return ResponseEntity.created(
                        URI.create(IntegrationApi.PATH_CREATE_DELIVERY_TRIP + "/" + tripId + "/drops/" + deliveryId))
                .body(toResponse(drop));
    }

    @Override
    public ResponseEntity<DeliveryDropResponse> updateDeliveryStatus(
            UUID deliveryId, String idempotencyKey, UpdateDeliveryStatusRequest request) {
        ScopeContext scope = currentScope.get();
        updateDeliveryStatus.handle(
                new UpdateDeliveryStatus(
                        deliveryId, request.getStatus().getValue(), request.getFailureReason(), request.getNotes()),
                scope);
        DeliveryDropView drop =
                deliveryQueries.getDeliveryDrop(deliveryId, scope).orElseThrow();
        return ResponseEntity.ok(toResponse(drop));
    }

    @Override
    public ResponseEntity<DailyDeliverySummaryResponse> getDailyDeliverySummary(LocalDate date, UUID locationId) {
        DailyDeliverySummaryView summary =
                deliveryQueries.getDailyDeliverySummary(date, locationId, currentScope.get());
        return ResponseEntity.ok(toResponse(summary));
    }

    @Override
    public ResponseEntity<MonthlyDeliveryReportResponse> getMonthlyDeliveryReport(String period, UUID locationId) {
        MonthlyDeliveryReportView report =
                deliveryQueries.getMonthlyDeliveryReport(period, locationId, currentScope.get());
        return ResponseEntity.ok(toResponse(report));
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

    private static LiveVehicleLocationResponse toResponse(LiveVehicleLocationView v) {
        return new LiveVehicleLocationResponse(
                        v.vehicleId(), v.deviceId(), v.latitude(), v.longitude(), v.speedKmh(), v.recordedAt())
                .tripId(v.tripId())
                .headingDegrees(v.headingDegrees())
                .batteryPercentage(v.batteryPercentage());
    }

    private static DeliveryTripResponse toResponse(DeliveryTripSummaryView t) {
        return new DeliveryTripResponse(
                        t.tripId(),
                        t.ownerEntityId(),
                        t.tripReference(),
                        t.vehicleId(),
                        t.originLocationId(),
                        t.tripDate(),
                        DeliveryTripResponse.TripStatusEnum.fromValue(t.tripStatus()),
                        t.totalDistanceKm(),
                        t.totalDrops(),
                        t.deliveredDrops(),
                        t.failedDrops())
                .driverCode(t.driverCode())
                .destinationLocationId(t.destinationLocationId())
                .startedAt(t.startedAt())
                .completedAt(t.completedAt());
    }

    private static DeliveryDropResponse toResponse(DeliveryDropView d) {
        return new DeliveryDropResponse(
                        d.deliveryId(),
                        d.tripId(),
                        d.deliveryReference(),
                        d.destinationLocationId(),
                        d.packagesCount(),
                        d.weightKg(),
                        DeliveryDropResponse.StatusEnum.fromValue(d.status()))
                .recipientRef(d.recipientRef())
                .scheduledAt(d.scheduledAt())
                .deliveredAt(d.deliveredAt())
                .failureReason(d.failureReason())
                .notes(d.notes());
    }

    private static DailyDeliverySummaryResponse toResponse(DailyDeliverySummaryView s) {
        List<DeliveryTripResponse> tripResponses =
                s.trips().stream().map(IntegrationController::toResponse).toList();
        return new DailyDeliverySummaryResponse(
                s.date(),
                s.totalTrips(),
                s.completedTrips(),
                s.totalDeliveries(),
                s.deliveredCount(),
                s.failedCount(),
                s.pendingCount(),
                s.completionRatePercent(),
                s.totalDistanceKm(),
                s.activeVehiclesCount(),
                tripResponses);
    }

    private static MonthlyDeliveryReportResponse toResponse(MonthlyDeliveryReportView m) {
        List<DailyDeliveryBreakdownItem> dailyItems = m.dailyBreakdown().stream()
                .map(d -> new DailyDeliveryBreakdownItem(
                        d.date(),
                        d.totalTrips(),
                        d.totalDeliveries(),
                        d.deliveredCount(),
                        d.failedCount(),
                        d.totalDistanceKm()))
                .toList();
        List<VehicleDeliveryBreakdownItem> vehicleItems = m.vehicleBreakdown().stream()
                .map(v -> new VehicleDeliveryBreakdownItem(
                        v.vehicleId(),
                        v.totalTrips(),
                        v.totalDeliveries(),
                        v.deliveredCount(),
                        v.failedCount(),
                        v.totalDistanceKm()))
                .toList();
        return new MonthlyDeliveryReportResponse(
                m.periodMonth(),
                m.totalTrips(),
                m.completedTrips(),
                m.totalDeliveries(),
                m.deliveredCount(),
                m.failedCount(),
                m.deliverySuccessRate(),
                m.totalDistanceKm(),
                dailyItems,
                vehicleItems);
    }

    @Override
    public ResponseEntity<PaymentIntentResponse> initiatePayment(String idempotencyKey, InitiatePaymentRequest req) {
        ScopeContext scope = currentScope.get();
        InitiatePayment cmd = new InitiatePayment(
                req.getPaymentId(),
                req.getOrderReference(),
                req.getCustomerRef(),
                req.getAmount(),
                req.getPaymentMethod() != null ? req.getPaymentMethod().getValue() : null,
                req.getProvider(),
                req.getDescription(),
                req.getIdempotencyKey() != null ? req.getIdempotencyKey() : idempotencyKey,
                req.getMetadata());
        PaymentIntentView view = initiatePayment.handle(cmd, scope);
        return ResponseEntity.created(URI.create("/v1/integration/payments/" + view.paymentId()))
                .body(toPaymentResponse(view));
    }

    @Override
    public ResponseEntity<PaymentIntentResponse> confirmPayment(
            UUID paymentId, String idempotencyKey, ConfirmPaymentRequest req) {
        ScopeContext scope = currentScope.get();
        ConfirmPayment cmd = new ConfirmPayment(
                paymentId,
                req != null ? req.getProviderRef() : null,
                req != null && req.getStatus() != null ? req.getStatus().getValue() : null,
                req != null ? req.getFailureReason() : null,
                req != null ? req.getMetadata() : Map.of());
        PaymentIntentView view = confirmPayment.handle(cmd, scope);
        return ResponseEntity.ok(toPaymentResponse(view));
    }

    @Override
    public ResponseEntity<PaymentIntentResponse> confirmCodPayment(
            UUID paymentId, String idempotencyKey, ConfirmCodPaymentRequest req) {
        ScopeContext scope = currentScope.get();
        ConfirmCodSettlement cmd = new ConfirmCodSettlement(
                paymentId, req != null ? req.getReceiptReference() : null, req != null ? req.getMetadata() : Map.of());
        PaymentIntentView view = confirmCodPayment.handle(cmd, scope);
        return ResponseEntity.ok(toPaymentResponse(view));
    }

    @Override
    public ResponseEntity<PaymentIntentResponse> retryPayment(
            UUID paymentId, String idempotencyKey, RetryPaymentRequest req) {
        ScopeContext scope = currentScope.get();
        String provider = req != null ? req.getProvider() : null;
        RetryPayment cmd = new RetryPayment(paymentId, provider);
        PaymentIntentView view = retryPayment.handle(cmd, scope);
        return ResponseEntity.ok(toPaymentResponse(view));
    }

    @Override
    public ResponseEntity<PaymentIntentResponse> getPayment(UUID paymentId) {
        ScopeContext scope = currentScope.get();
        return paymentQueries
                .findById(paymentId, scope)
                .map(this::toPaymentResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<List<PaymentIntentResponse>> listPaymentsByOrder(String orderReference) {
        ScopeContext scope = currentScope.get();
        List<PaymentIntentResponse> list = paymentQueries.findByOrderReference(orderReference, scope).stream()
                .map(this::toPaymentResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    private PaymentIntentResponse toPaymentResponse(PaymentIntentView v) {
        PaymentIntentResponse resp = new PaymentIntentResponse();
        resp.setPaymentId(v.paymentId());
        resp.setOwnerEntityId(v.ownerEntityId());
        resp.setOrderReference(v.orderReference());
        resp.setCustomerRef(v.customerRef());
        resp.setAmount(v.amount());
        resp.setCurrency(v.currency());
        resp.setPaymentMethod(v.paymentMethod());
        resp.setStatus(v.status());
        resp.setProvider(v.provider());
        resp.setProviderRef(v.providerRef());
        resp.setQrPayload(v.qrPayload());
        resp.setDescription(v.description());
        resp.setFailureReason(v.failureReason());
        resp.setIdempotencyKey(v.idempotencyKey());
        resp.setMetadata(v.metadata() != null ? v.metadata() : Map.of());
        resp.setInitiatedAt(v.initiatedAt());
        resp.setSettledAt(v.settledAt());
        resp.setCreatedAt(v.createdAt());
        resp.setUpdatedAt(v.updatedAt());
        return resp;
    }

    static String fileName(JournalExportView export) {
        return "journal_" + export.periodFrom() + "_" + export.periodTo()
                + (export.provisional() ? "_PROVISIONAL" : "")
                + "_" + export.exportId().toString().substring(24) + ".csv";
    }
}
