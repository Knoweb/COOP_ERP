package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.ApproveOvertime;
import lk.coopfed.knoweb.m9integration.api.AttendanceImportResult;
import lk.coopfed.knoweb.m9integration.api.AttendanceImported;
import lk.coopfed.knoweb.m9integration.api.EmployeeRegistered;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords;
import lk.coopfed.knoweb.m9integration.api.ImportAttendanceRecords.AttendanceImportItem;
import lk.coopfed.knoweb.m9integration.api.OvertimeApproved;
import lk.coopfed.knoweb.m9integration.api.RegisterEmployee;
import lk.coopfed.knoweb.m9integration.internal.hr.ApproveOvertimeHandler;
import lk.coopfed.knoweb.m9integration.internal.hr.ImportAttendanceRecordsHandler;
import lk.coopfed.knoweb.m9integration.internal.hr.RegisterEmployeeHandler;
import lk.coopfed.knoweb.m9integration.query.ApprovedPayrollContributionQueries;
import lk.coopfed.knoweb.m9integration.query.ApprovedPayrollContributionQueries.ApprovedPayrollContributionView;
import lk.coopfed.knoweb.m9integration.query.ApprovedPayrollContributionQueries.PayrollContributionSummary;
import lk.coopfed.knoweb.m9integration.query.AttendanceQueries;
import lk.coopfed.knoweb.m9integration.query.AttendanceQueries.OvertimeCalculationView;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries.EmployeeView;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.m9integration.internal.snapshot.EmployeeSnapshotContributor;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag("integration")
class AttendanceOvertimePayrollPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER_ENTITY = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e9c0-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");

    @Autowired
    Handles<RegisterEmployee, UUID> registerEmployee;

    @Autowired
    Handles<ImportAttendanceRecords, AttendanceImportResult> importAttendance;

    @Autowired
    Handles<ApproveOvertime, UUID> approveOvertime;

    @Autowired
    EmployeeSnapshotContributor employeeSnapshotContributor;

    @Autowired
    SystemScope transactions;

    @Autowired
    EmployeeQueries employeeQueries;

    @Autowired
    AttendanceQueries attendanceQueries;

    @Autowired
    ApprovedPayrollContributionQueries payrollQueries;

    @Autowired
    TestRestTemplate http;

    private ScopeContext ownerScope() {
        Scope s = new Scope(OWNER_ENTITY, null);
        return new ScopeContext(
                USER_ID, null, OWNER_ENTITY, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private ScopeContext otherScope() {
        Scope s = new Scope(OTHER_ENTITY, null);
        return new ScopeContext(
                USER_ID, null, OTHER_ENTITY, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static HttpHeaders as(UUID entity) {
        return TestIdentityProvider.entityWideHeaders(USER_ID, entity);
    }

    private ResponseEntity<JsonNode> getHttp(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postHttp(String url, Object body, HttpHeaders headers) {
        HttpHeaders withKey = new HttpHeaders();
        withKey.addAll(headers);
        withKey.set("Idempotency-Key", Ids.next().toString());
        withKey.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, withKey), JsonNode.class);
    }

    @BeforeEach
    void cleanTables() {
        superuserJdbc()
                .execute("truncate table integration.payroll_contribution, integration.overtime_calculation, "
                        + "integration.attendance_record, integration.hr_employee restart identity cascade");
        kernel.reset();
    }

    @Test
    void employeeLifecycleAndAttendanceOvertimeApprovalEndToEnd() {
        ScopeContext scope = ownerScope();

        // 1. Register employee
        UUID empId = registerEmployee.handle(
                new RegisterEmployee(
                        "EMP001", "Sunil Jayawardena", "198512345678", "EPF123", new BigDecimal("400.00"), null),
                scope);
        assertThat(empId).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(RegisterEmployeeHandler.AUDIT_REGISTERED);
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(EmployeeRegistered.class);
        kernel.reset();

        Optional<EmployeeView> empView = employeeQueries.lookupByEmployeeNo("EMP001", scope);
        assertThat(empView).isPresent();
        assertThat(empView.get().fullName()).isEqualTo("Sunil Jayawardena");
        assertThat(empView.get().hourlyRate()).isEqualByComparingTo("400.00");

        // Update employee hourly rate
        registerEmployee.handle(
                new RegisterEmployee(
                        "EMP001", "Sunil Jayawardena", "198512345678", "EPF123", new BigDecimal("500.00"), null),
                scope);
        EmployeeView updatedEmp = employeeQueries.getEmployee(empId, scope).orElseThrow();
        assertThat(updatedEmp.hourlyRate()).isEqualByComparingTo("500.00");
        kernel.reset();

        // 2. Ingest punch records:
        // Item 1: Weekday 2026-10-21 (Wednesday) 08:00 to 17:30 (570 mins worked).
        // Standard: 480 mins. Excess: 90 mins >= 30 mins threshold.
        // Overtime: 90 mins. Multiplier: 1.50.
        // Amount: 500.00 * 1.50 * 90 / 60 = 1125.00.
        AttendanceImportItem weekdayPunch = new AttendanceImportItem(
                "REC-PUNCH-001",
                "EMP001",
                "FP-DEV-01",
                "ZKTECO",
                LocalDate.of(2026, 10, 21),
                Instant.parse("2026-10-21T08:00:00Z"),
                Instant.parse("2026-10-21T17:30:00Z"),
                "{\"device\":\"FP-DEV-01\"}");

        // Item 2: Sub-threshold punch: 500 mins worked (excess 20 mins < 30 mins threshold) -> NO_OT_EARNED
        AttendanceImportItem subThresholdPunch = new AttendanceImportItem(
                "REC-PUNCH-002",
                "EMP001",
                "FP-DEV-01",
                "ZKTECO",
                LocalDate.of(2026, 10, 22),
                Instant.parse("2026-10-22T08:00:00Z"),
                Instant.parse("2026-10-22T16:20:00Z"),
                "{\"device\":\"FP-DEV-01\"}");

        // Item 3: Weekend punch: 2026-10-24 (Saturday) 08:00 to 17:00 (540 mins worked).
        // Standard: 480 mins. Excess: 60 mins >= 30 mins.
        // Weekend multiplier: 2.00.
        // Amount: 500.00 * 2.00 * 60 / 60 = 1000.00.
        AttendanceImportItem weekendPunch = new AttendanceImportItem(
                "REC-PUNCH-003",
                "EMP001",
                "FP-DEV-01",
                "ZKTECO",
                LocalDate.of(2026, 10, 24),
                Instant.parse("2026-10-24T08:00:00Z"),
                Instant.parse("2026-10-24T17:00:00Z"),
                "{\"device\":\"FP-DEV-01\"}");

        AttendanceImportResult result = importAttendance.handle(
                new ImportAttendanceRecords(List.of(weekdayPunch, subThresholdPunch, weekendPunch)), scope);

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.duplicate()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(ImportAttendanceRecordsHandler.AUDIT_IMPORTED);
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(AttendanceImported.class);
        AttendanceImported attEvent =
                (AttendanceImported) kernel.committedEvents().get(0);
        assertThat(attEvent.importedCount()).isEqualTo(3);
        assertThat(attEvent.duplicateCount()).isZero();
        assertThat(attEvent.rejectedCount()).isZero();
        kernel.reset();

        // Test Deduplication: repeat the same batch
        AttendanceImportResult dupResult =
                importAttendance.handle(new ImportAttendanceRecords(List.of(weekdayPunch)), scope);
        assertThat(dupResult.imported()).isZero();
        assertThat(dupResult.duplicate()).isEqualTo(1);
        assertThat(dupResult.rejected()).isZero();
        assertThat(kernel.committedEvents()).isEmpty();
        kernel.reset();

        // Test Rejection: invalid clock times & unknown employee
        AttendanceImportItem invalidTimePunch = new AttendanceImportItem(
                "REC-PUNCH-BAD-TIME",
                "EMP001",
                "FP-DEV-01",
                "ZKTECO",
                LocalDate.of(2026, 10, 25),
                Instant.parse("2026-10-25T17:00:00Z"),
                Instant.parse("2026-10-25T08:00:00Z"),
                "{}");
        AttendanceImportItem unknownEmpPunch = new AttendanceImportItem(
                "REC-PUNCH-UNKNOWN-EMP",
                "EMP999",
                "FP-DEV-01",
                "ZKTECO",
                LocalDate.of(2026, 10, 25),
                Instant.parse("2026-10-25T08:00:00Z"),
                Instant.parse("2026-10-25T17:00:00Z"),
                "{}");
        AttendanceImportResult badResult =
                importAttendance.handle(new ImportAttendanceRecords(List.of(invalidTimePunch, unknownEmpPunch)), scope);
        assertThat(badResult.rejected()).isEqualTo(2);
        assertThat(kernel.committedEvents()).isEmpty();
        kernel.reset();

        // 3. Verify Calculations
        List<OvertimeCalculationView> pendingOt = attendanceQueries.listOvertime("PENDING_APPROVAL", null, scope);
        assertThat(pendingOt).hasSize(2);

        OvertimeCalculationView weekdayOt = pendingOt.stream()
                .filter(o -> o.businessDate().equals(LocalDate.of(2026, 10, 21)))
                .findFirst()
                .orElseThrow();
        assertThat(weekdayOt.workedMinutes()).isEqualTo(570);
        assertThat(weekdayOt.standardMinutes()).isEqualTo(480);
        assertThat(weekdayOt.calculatedOtMinutes()).isEqualTo(90);
        assertThat(weekdayOt.otMultiplier()).isEqualByComparingTo("1.50");
        assertThat(weekdayOt.calculatedAmount()).isEqualByComparingTo("1125.00");
        assertThat(weekdayOt.status()).isEqualTo("PENDING_APPROVAL");

        OvertimeCalculationView weekendOt = pendingOt.stream()
                .filter(o -> o.businessDate().equals(LocalDate.of(2026, 10, 24)))
                .findFirst()
                .orElseThrow();
        assertThat(weekendOt.workedMinutes()).isEqualTo(540);
        assertThat(weekendOt.calculatedOtMinutes()).isEqualTo(60);
        assertThat(weekendOt.otMultiplier()).isEqualByComparingTo("2.00");
        assertThat(weekendOt.calculatedAmount()).isEqualByComparingTo("1000.00");

        List<OvertimeCalculationView> noOt = attendanceQueries.listOvertime("NO_OT_EARNED", null, scope);
        assertThat(noOt).hasSize(1);
        assertThat(noOt.get(0).calculatedOtMinutes()).isZero();
        assertThat(noOt.get(0).calculatedAmount()).isEqualByComparingTo("0.00");

        // 4. Approve Overtime for Payroll Contribution
        UUID contributionId = approveOvertime.handle(
                new ApproveOvertime(weekdayOt.calculationId(), "2026-10", "Manager approved weekday overtime"), scope);
        assertThat(contributionId).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(ApproveOvertimeHandler.AUDIT_APPROVED);
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(OvertimeApproved.class);
        OvertimeApproved event = (OvertimeApproved) kernel.committedEvents().get(0);
        assertThat(event.contributionId()).isEqualTo(contributionId);
        assertThat(event.payrollPeriod()).isEqualTo("2026-10");
        assertThat(event.approvedOtMinutes()).isEqualTo(90);
        assertThat(event.approvedAmount()).isEqualByComparingTo("1125.00");
        kernel.reset();

        // Calculation status is now APPROVED
        OvertimeCalculationView updatedCalc =
                attendanceQueries.getOvertime(weekdayOt.calculationId(), scope).orElseThrow();
        assertThat(updatedCalc.status()).isEqualTo("APPROVED");

        // Double approval prevented
        assertThatThrownBy(() -> approveOvertime.handle(
                        new ApproveOvertime(weekdayOt.calculationId(), "2026-10", "Second attempt"), scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.overtime.already_processed");

        // Non-qualifying approval prevented
        assertThatThrownBy(() -> approveOvertime.handle(
                        new ApproveOvertime(noOt.get(0).calculationId(), "2026-10", "Try approve no-ot"), scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.overtime.not_qualifying");

        // 5. Query approved contributions seam (M1-09 contract)
        List<ApprovedPayrollContributionView> contributions =
                payrollQueries.listApprovedContributions("2026-10", scope);
        assertThat(contributions).hasSize(1);
        ApprovedPayrollContributionView contrib = contributions.get(0);
        assertThat(contrib.contributionId()).isEqualTo(contributionId);
        assertThat(contrib.employeeNo()).isEqualTo("EMP001");
        assertThat(contrib.payrollPeriod()).isEqualTo("2026-10");
        assertThat(contrib.approvedOtMinutes()).isEqualTo(90);
        assertThat(contrib.approvedAmount()).isEqualByComparingTo("1125.00");
        assertThat(contrib.status()).isEqualTo("APPROVED_PENDING_PAYROLL");
        assertThat(contrib.approvalNotes()).isEqualTo("Manager approved weekday overtime");

        PayrollContributionSummary summary = payrollQueries.getPeriodSummary("2026-10", scope);
        assertThat(summary.payrollPeriod()).isEqualTo("2026-10");
        assertThat(summary.totalEmployees()).isEqualTo(1);
        assertThat(summary.totalOtMinutes()).isEqualTo(90);
        assertThat(summary.totalApprovedAmount()).isEqualByComparingTo("1125.00");
        assertThat(summary.contributionCount()).isEqualTo(1);

        // 6. RLS Tenant Isolation: otherScope sees nothing
        ScopeContext other = otherScope();
        assertThat(employeeQueries.listEmployees(other)).isEmpty();
        assertThat(attendanceQueries.listAttendance(null, null, other)).isEmpty();
        assertThat(attendanceQueries.listOvertime(null, null, other)).isEmpty();
        assertThat(payrollQueries.listApprovedContributions("2026-10", other)).isEmpty();
        PayrollContributionSummary otherSummary = payrollQueries.getPeriodSummary("2026-10", other);
        assertThat(otherSummary.contributionCount()).isZero();
        assertThat(otherSummary.totalApprovedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void httpEndpointsWorkEndToEnd() {
        // 1. POST /v1/integration/hr/employees
        ResponseEntity<JsonNode> reg = postHttp(
                "/v1/integration/hr/employees",
                Map.of(
                        "employeeNo", "EMP-HTTP-1",
                        "fullName", "Anura Bandara",
                        "hourlyRate", 450.00),
                as(OWNER_ENTITY));
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String empId = reg.getBody().path("employeeId").asText();

        // 2. GET /v1/integration/hr/employees
        ResponseEntity<JsonNode> empList = getHttp("/v1/integration/hr/employees", as(OWNER_ENTITY));
        assertThat(empList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empList.getBody().isArray()).isTrue();

        // 3. GET /v1/integration/hr/employees/{employeeId}
        ResponseEntity<JsonNode> empGet = getHttp("/v1/integration/hr/employees/" + empId, as(OWNER_ENTITY));
        assertThat(empGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empGet.getBody().path("employeeNo").asText()).isEqualTo("EMP-HTTP-1");

        // 4. POST /v1/integration/attendance/import
        ResponseEntity<JsonNode> imp = postHttp(
                "/v1/integration/attendance/import",
                Map.of(
                        "items",
                        List.of(Map.of(
                                "externalRecordId", "PUNCH-HTTP-1",
                                "employeeNo", "EMP-HTTP-1",
                                "deviceIdentifier", "DEV-01",
                                "sourceProvider", "ZKTECO",
                                "businessDate", "2026-10-21",
                                "clockIn", "2026-10-21T08:00:00Z",
                                "clockOut", "2026-10-21T17:30:00Z"))),
                as(OWNER_ENTITY));
        assertThat(imp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imp.getBody().path("imported").asInt()).isEqualTo(1);

        // 5. GET /v1/integration/attendance
        ResponseEntity<JsonNode> attList =
                getHttp("/v1/integration/attendance?businessDate=2026-10-21", as(OWNER_ENTITY));
        assertThat(attList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attList.getBody()).hasSize(1);

        // 6. GET /v1/integration/overtime
        ResponseEntity<JsonNode> otList = getHttp("/v1/integration/overtime?status=PENDING_APPROVAL", as(OWNER_ENTITY));
        assertThat(otList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otList.getBody()).hasSize(1);
        String calcId = otList.getBody().get(0).path("calculationId").asText();

        // 7. POST /v1/integration/overtime/{calculationId}/approve
        ResponseEntity<JsonNode> app = postHttp(
                "/v1/integration/overtime/" + calcId + "/approve",
                Map.of("payrollPeriod", "2026-10", "notes", "Approved via HTTP"),
                as(OWNER_ENTITY));
        assertThat(app.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(app.getBody().path("approvedOtMinutes").asInt()).isEqualTo(90);

        // 8. GET /v1/integration/payroll-contributions
        ResponseEntity<JsonNode> contribList =
                getHttp("/v1/integration/payroll-contributions?payrollPeriod=2026-10", as(OWNER_ENTITY));
        assertThat(contribList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contribList.getBody()).hasSize(1);

        // 9. GET /v1/integration/payroll-contributions/summary
        ResponseEntity<JsonNode> summary =
                getHttp("/v1/integration/payroll-contributions/summary?payrollPeriod=2026-10", as(OWNER_ENTITY));
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody().path("totalEmployees").asInt()).isEqualTo(1);
        assertThat(summary.getBody().path("totalOtMinutes").asInt()).isEqualTo(90);
    }
    @Test
    void employeeSnapshotContributorPublishesSanitizedEligibilityOnly() {
        ScopeContext scope = ownerScope();
        UUID shopLoc = UUID.randomUUID();
        UUID empId = registerEmployee.handle(
                new RegisterEmployee(
                        "EMP-SNAP-01", "Sunil Jayawardena", "198512345678", "EPF123", new BigDecimal("400.00"), shopLoc),
                scope);

        var shop = new SnapshotContributor.Shop(OWNER_ENTITY, shopLoc);
        var rows = transactions.inScope(
                SystemScope.own(OWNER_ENTITY, shopLoc),
                () -> employeeSnapshotContributor.allRows(EmployeeSnapshotContributor.EMPLOYEE_DISCOUNT_ELIGIBILITY, shop));

        assertThat(rows).containsKey(empId);
        Map<String, Object> row = rows.get(empId);
        assertThat(row)
                .containsEntry("employee_no", "EMP-SNAP-01")
                .containsEntry("active", true)
                .containsEntry("location_id", shopLoc.toString())
                .doesNotContainKeys("full_name", "nic", "epf_number", "hourly_rate");
    }
}
