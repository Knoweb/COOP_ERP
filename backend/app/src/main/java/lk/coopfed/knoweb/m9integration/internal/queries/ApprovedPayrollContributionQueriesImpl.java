package lk.coopfed.knoweb.m9integration.internal.queries;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.query.ApprovedPayrollContributionQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ApprovedPayrollContributionQueriesImpl implements ApprovedPayrollContributionQueries {

    private final JdbcTemplate jdbc;

    public ApprovedPayrollContributionQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ApprovedPayrollContributionView> CONTRIBUTION_MAPPER = (rs, i) -> {
        Timestamp approvedTs = rs.getTimestamp("approved_at");
        Timestamp createdTs = rs.getTimestamp("created_at");
        return new ApprovedPayrollContributionView(
                rs.getObject("contribution_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_no"),
                rs.getString("full_name"),
                rs.getObject("calculation_id", UUID.class),
                rs.getString("payroll_period"),
                rs.getString("contribution_type"),
                rs.getInt("approved_ot_minutes"),
                rs.getBigDecimal("approved_amount"),
                rs.getObject("approved_by", UUID.class),
                approvedTs != null ? approvedTs.toInstant() : null,
                rs.getString("approval_notes"),
                rs.getString("status"),
                createdTs != null ? createdTs.toInstant() : null);
    };

    @Override
    public List<ApprovedPayrollContributionView> listApprovedContributions(String payrollPeriod, ScopeContext scope) {
        StringBuilder sql = new StringBuilder(
                """
                select p.contribution_id, p.owner_entity_id, p.employee_id, e.employee_no, e.full_name,
                       p.calculation_id, p.payroll_period, p.contribution_type, p.approved_ot_minutes,
                       p.approved_amount, p.approved_by, p.approved_at, p.approval_notes, p.status, p.created_at
                  from integration.payroll_contribution p
                  join integration.hr_employee e on e.employee_id = p.employee_id
                 where 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (payrollPeriod != null && !payrollPeriod.isBlank()) {
            sql.append(" and p.payroll_period = ?");
            params.add(payrollPeriod.trim());
        }
        sql.append(" order by p.approved_at desc");

        return jdbc.query(sql.toString(), CONTRIBUTION_MAPPER, params.toArray());
    }

    @Override
    public PayrollContributionSummary getPeriodSummary(String payrollPeriod, ScopeContext scope) {
        String period = payrollPeriod != null ? payrollPeriod.trim() : "";
        return jdbc.query(
                """
                select count(distinct employee_id) as total_employees,
                       coalesce(sum(approved_ot_minutes), 0) as total_ot_minutes,
                       coalesce(sum(approved_amount), 0) as total_approved_amount,
                       count(*) as contribution_count
                  from integration.payroll_contribution
                 where payroll_period = ?
                """,
                rs -> {
                    if (rs.next()) {
                        return new PayrollContributionSummary(
                                period,
                                rs.getInt("total_employees"),
                                rs.getInt("total_ot_minutes"),
                                rs.getBigDecimal("total_approved_amount"),
                                rs.getInt("contribution_count"));
                    }
                    return new PayrollContributionSummary(period, 0, 0, BigDecimal.ZERO, 0);
                },
                period);
    }

    @Override
    public Optional<ApprovedPayrollContributionView> getContribution(UUID contributionId, ScopeContext scope) {
        if (contributionId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                select p.contribution_id, p.owner_entity_id, p.employee_id, e.employee_no, e.full_name,
                       p.calculation_id, p.payroll_period, p.contribution_type, p.approved_ot_minutes,
                       p.approved_amount, p.approved_by, p.approved_at, p.approval_notes, p.status, p.created_at
                  from integration.payroll_contribution p
                  join integration.hr_employee e on e.employee_id = p.employee_id
                 where p.contribution_id = ?
                """,
                        CONTRIBUTION_MAPPER,
                        contributionId)
                .stream()
                .findFirst();
    }
}
