package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Published queries for approved payroll contributions. This is the authoritative producer seam
 * consumed by downstream payroll and final accounts (M1-09).
 */
public interface ApprovedPayrollContributionQueries {

    List<ApprovedPayrollContributionView> listApprovedContributions(String payrollPeriod, ScopeContext scope);

    PayrollContributionSummary getPeriodSummary(String payrollPeriod, ScopeContext scope);

    Optional<ApprovedPayrollContributionView> getContribution(UUID contributionId, ScopeContext scope);

    record ApprovedPayrollContributionView(
            UUID contributionId,
            UUID ownerEntityId,
            UUID employeeId,
            String employeeNo,
            String employeeName,
            UUID calculationId,
            String payrollPeriod,
            String contributionType,
            int approvedOtMinutes,
            BigDecimal approvedAmount,
            UUID approvedBy,
            Instant approvedAt,
            String approvalNotes,
            String status,
            Instant createdAt) {}

    record PayrollContributionSummary(
            String payrollPeriod,
            int totalEmployees,
            int totalOtMinutes,
            BigDecimal totalApprovedAmount,
            int contributionCount) {}
}
