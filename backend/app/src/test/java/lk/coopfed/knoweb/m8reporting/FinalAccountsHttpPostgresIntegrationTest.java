package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FinalAccountsHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID ENTITY_B = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID USER_A = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID USER_B = UUID.fromString("0190e888-0000-7000-8000-000000000020");
    private static final AtomicLong MOVEMENT_SEQ = new AtomicLong(100);

    @Autowired
    private TestRestTemplate http;

    private UUID shopLocA;
    private UUID shopLocB;

    @BeforeEach
    void setUp() {
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_line_fact CASCADE");
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_fact CASCADE");
        superuserJdbc().execute("TRUNCATE inventory.stock_movement CASCADE");
        superuserJdbc().execute("TRUNCATE integration.payroll_contribution CASCADE");
        superuserJdbc().execute("TRUNCATE integration.overtime_calculation CASCADE");
        superuserJdbc().execute("TRUNCATE integration.attendance_record CASCADE");
        superuserJdbc().execute("TRUNCATE integration.hr_employee CASCADE");
        superuserJdbc()
                .execute(
                        "DELETE FROM party.location WHERE owner_entity_id IN ('" + ENTITY_A + "', '" + ENTITY_B + "')");

        shopLocA = Ids.next();
        shopLocB = Ids.next();

        insertLocation(shopLocA, ENTITY_A, "LOC-SHOP-A", "Shop A Location");
        insertLocation(shopLocB, ENTITY_B, "LOC-SHOP-B", "Shop B Location");
    }

    private void insertLocation(UUID locId, UUID entityId, String code, String name) {
        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        locId,
                        entityId,
                        code,
                        name);
    }

    private void insertSaleFact(UUID entityId, UUID locId, String date, String docNo, double net, double tax) {
        UUID rcpId = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, ?::date, ?, ?, ?, ?, 1, now(), ?)
                """,
                        rcpId,
                        entityId,
                        locId,
                        date,
                        docNo,
                        BigDecimal.valueOf(net),
                        BigDecimal.valueOf(tax),
                        BigDecimal.valueOf(net + tax),
                        Ids.next());
    }

    private void insertStockSaleMovement(UUID entityId, UUID locId, String date, double qty, double unitCost) {
        superuserJdbc()
                .update(
                        """
                INSERT INTO inventory.stock_movement
                       (movement_id, received_at, source, movement_seq, owner_entity_id, location_id,
                        batch_id, sku_id, condition, qty_delta, movement_type, unit_cost_at_movement,
                        document_id, occurred_at)
                VALUES (?, now(), 'central', ?, ?, ?,
                        ?, ?, 'GOOD', ?, 'SALE', ?,
                        ?, (? || ' 12:00:00+05:30')::timestamptz)
                """,
                        Ids.next(),
                        MOVEMENT_SEQ.getAndIncrement(),
                        entityId,
                        locId,
                        Ids.next(),
                        Ids.next(),
                        BigDecimal.valueOf(-qty),
                        BigDecimal.valueOf(unitCost),
                        Ids.next(),
                        date);
    }

    private UUID insertHrEmployee(UUID entityId, String empNo, String name) {
        UUID empId = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO integration.hr_employee (employee_id, owner_entity_id, employee_no, full_name, hourly_rate, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 250.00, 'ACTIVE', now(), now())
                """,
                        empId,
                        entityId,
                        empNo,
                        name);
        return empId;
    }

    private void insertApprovedPayrollContribution(
            UUID entityId, UUID employeeId, String period, double amount, int otMinutes, String status) {
        UUID attId = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO integration.attendance_record (attendance_id, owner_entity_id, external_record_id, employee_id, device_identifier, source_provider, business_date, clock_in, clock_out, worked_minutes, status, created_at)
                VALUES (?, ?, ?, ?, 'DEV-01', 'BIO-FINGER', (? || '-10')::date, now(), now(), 600, 'VALIDATED', now())
                """,
                        attId,
                        entityId,
                        Ids.next().toString(),
                        employeeId,
                        period);

        UUID calcId = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO integration.overtime_calculation (calculation_id, attendance_id, owner_entity_id, employee_id, business_date, worked_minutes, standard_minutes, qualifying_ot_minutes, calculated_ot_minutes, calculated_amount, status, created_at)
                VALUES (?, ?, ?, ?, (? || '-10')::date, 600, 480, 120, ?, ?, 'APPROVED', now())
                """,
                        calcId,
                        attId,
                        entityId,
                        employeeId,
                        period,
                        otMinutes,
                        BigDecimal.valueOf(amount));

        UUID contribId = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO integration.payroll_contribution (contribution_id, owner_entity_id, employee_id, calculation_id, payroll_period, contribution_type, approved_ot_minutes, approved_amount, approved_by, approved_at, approval_notes, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'OVERTIME', ?, ?, ?, now(), 'Approved by supervisor', ?, now())
                """,
                        contribId,
                        entityId,
                        employeeId,
                        calcId,
                        period,
                        otMinutes,
                        BigDecimal.valueOf(amount),
                        USER_A,
                        status);
    }

    @Test
    void finalAccountsSuccessfulReconciliationWithSalesCogsAndApprovedPayroll() {
        // Arrange retail sales and inventory cost for ENTITY_A in October 2026
        insertSaleFact(ENTITY_A, shopLocA, "2026-10-05", "RCP-001", 100000.00, 0.00);
        insertStockSaleMovement(ENTITY_A, shopLocA, "2026-10-05", 100.0, 600.00); // 100 * 600 = 60000.00

        // Arrange approved payroll overtime contributions for ENTITY_A in October 2026
        UUID emp1 = insertHrEmployee(ENTITY_A, "EMP-101", "Kamal Perera");
        insertApprovedPayrollContribution(ENTITY_A, emp1, "2026-10", 12000.00, 180, "APPROVED_PENDING_PAYROLL");

        // Act
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/final-accounts?period=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body.get("period").asText()).isEqualTo("2026-10");
        assertThat(body.get("currency").asText()).isEqualTo("LKR");
        assertThat(body.get("status").asText()).isEqualTo("PARTIAL"); // other expenses pending Phase 3
        assertThat(new BigDecimal(body.get("revenue").asText())).isEqualByComparingTo("100000.00");
        assertThat(new BigDecimal(body.get("cogs").asText())).isEqualByComparingTo("60000.00");
        assertThat(body.get("costPending").asBoolean()).isFalse();
        assertThat(new BigDecimal(body.get("grossProfit").asText())).isEqualByComparingTo("40000.00");
        assertThat(new BigDecimal(body.get("grossProfitMarginPercent").asText()))
                .isEqualByComparingTo("40.00");

        assertThat(new BigDecimal(body.get("payrollExpenses").asText())).isEqualByComparingTo("12000.00");
        assertThat(body.get("totalExpenses") == null
                        || body.get("totalExpenses").isNull())
                .isTrue();
        assertThat(body.get("otherExpensesPending").asBoolean()).isTrue();

        assertThat(body.get("operatingProfit") == null
                        || body.get("operatingProfit").isNull())
                .isTrue();
        assertThat(body.get("operatingProfitMarginPercent") == null
                        || body.get("operatingProfitMarginPercent").isNull())
                .isTrue();

        // Expense categories check
        JsonNode categories = body.get("expenseCategories");
        assertThat(categories).isNotNull();
        assertThat(categories.isArray()).isTrue();
        assertThat(categories.size()).isGreaterThanOrEqualTo(2);

        JsonNode staffCat = categories.get(0);
        assertThat(staffCat.get("category").asText()).isEqualTo("STAFF_PAYROLL");
        assertThat(new BigDecimal(staffCat.get("amount").asText())).isEqualByComparingTo("12000.00");
        assertThat(staffCat.get("status").asText()).isEqualTo("APPROVED");

        JsonNode otherCat = categories.get(1);
        assertThat(otherCat.get("category").asText()).isEqualTo("OTHER_OPERATING");
        assertThat(otherCat.get("amount").isNull()).isTrue();
        assertThat(otherCat.get("status").asText()).isEqualTo("UNAVAILABLE");

        // Payroll contributions check
        JsonNode contributions = body.get("payrollContributions");
        assertThat(contributions).isNotNull();
        assertThat(contributions.isArray()).isTrue();
        assertThat(contributions.size()).isEqualTo(1);
        JsonNode c1 = contributions.get(0);
        assertThat(c1.get("employeeNo").asText()).isEqualTo("EMP-101");
        assertThat(c1.get("employeeName").asText()).isEqualTo("Kamal Perera");
        assertThat(c1.get("approvedOtMinutes").asInt()).isEqualTo(180);
        assertThat(new BigDecimal(c1.get("approvedAmount").asText())).isEqualByComparingTo("12000.00");
    }

    @Test
    void finalAccountsExpensesEndpointReturnsCategoriesAndDrilldown() {
        UUID emp1 = insertHrEmployee(ENTITY_A, "EMP-201", "Sunil Silva");
        insertApprovedPayrollContribution(ENTITY_A, emp1, "2026-10", 7500.00, 120, "POSTED_TO_PAYROLL");

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/final-accounts/expenses?period=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body.get("period").asText()).isEqualTo("2026-10");
        assertThat(new BigDecimal(body.get("totalPayrollExpenses").asText())).isEqualByComparingTo("7500.00");
        assertThat(body.get("categories").size()).isGreaterThanOrEqualTo(2);
        assertThat(body.get("contributions").size()).isEqualTo(1);
        assertThat(body.get("contributions").get(0).get("employeeNo").asText()).isEqualTo("EMP-201");
    }

    @Test
    void finalAccountsCsvExportProducesValidContent() {
        insertSaleFact(ENTITY_A, shopLocA, "2026-10-12", "RCP-CSV", 50000.00, 0.00);
        UUID emp1 = insertHrEmployee(ENTITY_A, "EMP-301", "Nimal Fernando");
        insertApprovedPayrollContribution(ENTITY_A, emp1, "2026-10", 5000.00, 60, "APPROVED_PENDING_PAYROLL");

        ResponseEntity<String> response = http.exchange(
                "/v1/reporting/final-accounts/csv?period=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER_A, ENTITY_A)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
        String csv = response.getBody();
        assertThat(csv).isNotNull();
        assertThat(csv).contains("FINAL ACCOUNTS STATEMENT");
        assertThat(csv).contains("Period,2026-10");
        assertThat(csv).contains("Revenue,50000.00");
        assertThat(csv).contains("Approved Payroll Expenses,5000.00");
        assertThat(csv).contains("Total Operating Expenses,UNAVAILABLE");
        assertThat(csv).contains("Operating Profit,UNAVAILABLE");
        assertThat(csv).contains("STAFF_PAYROLL");
        assertThat(csv).contains("Nimal Fernando");
    }

    @Test
    void cancelledPayrollContributionsAreExcluded() {
        UUID emp1 = insertHrEmployee(ENTITY_A, "EMP-401", "Kasun Bandara");
        // Insert one approved and one cancelled
        insertApprovedPayrollContribution(ENTITY_A, emp1, "2026-10", 8000.00, 100, "APPROVED_PENDING_PAYROLL");
        insertApprovedPayrollContribution(ENTITY_A, emp1, "2026-10", 3000.00, 40, "CANCELLED");

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/final-accounts?period=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(new BigDecimal(body.get("payrollExpenses").asText())).isEqualByComparingTo("8000.00");
        assertThat(body.get("payrollContributions").size()).isEqualTo(1);
    }

    @Test
    void tenantIsolationPreventsCrossEntityLeakage() {
        // ENTITY_A has sales and payroll
        insertSaleFact(ENTITY_A, shopLocA, "2026-10-15", "RCP-ENT-A", 45000.00, 0.00);
        UUID emp1 = insertHrEmployee(ENTITY_A, "EMP-A1", "Entity A Worker");
        insertApprovedPayrollContribution(ENTITY_A, emp1, "2026-10", 9000.00, 120, "APPROVED_PENDING_PAYROLL");

        // ENTITY_B queries the same period
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/final-accounts?period=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER_B, ENTITY_B)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(new BigDecimal(body.get("revenue").asText())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(body.get("payrollExpenses").asText())).isEqualByComparingTo("0.00");
        assertThat(body.get("payrollContributions").size()).isEqualTo(0);
    }

    @Test
    void invalidMonthThrowsBadRequest() {
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/final-accounts?period=invalid-month",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
