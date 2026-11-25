package lk.coopfed.knoweb.m9integration.internal.queries;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EmployeeQueriesImpl implements EmployeeQueries {

    private final JdbcTemplate jdbc;

    public EmployeeQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<EmployeeView> EMPLOYEE_MAPPER = (rs, i) -> {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new EmployeeView(
                rs.getObject("employee_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("employee_no"),
                rs.getString("full_name"),
                rs.getString("nic"),
                rs.getString("epf_no"),
                rs.getBigDecimal("hourly_rate"),
                rs.getObject("location_id", UUID.class),
                rs.getString("status"),
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null);
    };

    @Override
    public Optional<EmployeeView> lookupByEmployeeNo(String employeeNo, ScopeContext scope) {
        if (employeeNo == null || employeeNo.isBlank()) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                select employee_id, owner_entity_id, employee_no, full_name, nic, epf_no, hourly_rate,
                       location_id, status, created_at, updated_at
                  from integration.hr_employee
                 where employee_no = ?
                 order by created_at desc
                 limit 1
                """,
                        EMPLOYEE_MAPPER,
                        employeeNo.trim())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<EmployeeView> getEmployee(UUID employeeId, ScopeContext scope) {
        if (employeeId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                select employee_id, owner_entity_id, employee_no, full_name, nic, epf_no, hourly_rate,
                       location_id, status, created_at, updated_at
                  from integration.hr_employee
                 where employee_id = ?
                """,
                        EMPLOYEE_MAPPER,
                        employeeId)
                .stream()
                .findFirst();
    }

    @Override
    public List<EmployeeView> listEmployees(ScopeContext scope) {
        return jdbc.query(
                """
                select employee_id, owner_entity_id, employee_no, full_name, nic, epf_no, hourly_rate,
                       location_id, status, created_at, updated_at
                  from integration.hr_employee
                 order by employee_no asc
                """,
                EMPLOYEE_MAPPER);
    }
}
