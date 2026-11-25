package lk.coopfed.knoweb.m9integration.internal.queries;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.query.AttendanceQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AttendanceQueriesImpl implements AttendanceQueries {

    private final JdbcTemplate jdbc;

    public AttendanceQueriesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AttendanceRecordView> ATTENDANCE_MAPPER = (rs, i) -> {
        Timestamp inTs = rs.getTimestamp("clock_in");
        Timestamp outTs = rs.getTimestamp("clock_out");
        Timestamp createdTs = rs.getTimestamp("created_at");
        Date bDate = rs.getDate("business_date");
        return new AttendanceRecordView(
                rs.getObject("attendance_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("external_record_id"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_no"),
                rs.getString("full_name"),
                rs.getString("device_identifier"),
                rs.getString("source_provider"),
                bDate != null ? bDate.toLocalDate() : null,
                inTs != null ? inTs.toInstant() : null,
                outTs != null ? outTs.toInstant() : null,
                rs.getInt("worked_minutes"),
                rs.getString("status"),
                rs.getString("rejection_reason"),
                createdTs != null ? createdTs.toInstant() : null);
    };

    private static final RowMapper<OvertimeCalculationView> OVERTIME_MAPPER = (rs, i) -> {
        Date bDate = rs.getDate("business_date");
        Timestamp createdTs = rs.getTimestamp("created_at");
        return new OvertimeCalculationView(
                rs.getObject("calculation_id", UUID.class),
                rs.getObject("attendance_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_no"),
                rs.getString("full_name"),
                bDate != null ? bDate.toLocalDate() : null,
                rs.getInt("worked_minutes"),
                rs.getInt("standard_minutes"),
                rs.getInt("qualifying_ot_minutes"),
                rs.getInt("calculated_ot_minutes"),
                rs.getBigDecimal("ot_multiplier"),
                rs.getBigDecimal("calculated_amount"),
                rs.getString("status"),
                createdTs != null ? createdTs.toInstant() : null);
    };

    @Override
    public List<AttendanceRecordView> listAttendance(LocalDate businessDate, UUID employeeId, ScopeContext scope) {
        StringBuilder sql = new StringBuilder(
                """
                select a.attendance_id, a.owner_entity_id, a.external_record_id, a.employee_id,
                       e.employee_no, e.full_name, a.device_identifier, a.source_provider,
                       a.business_date, a.clock_in, a.clock_out, a.worked_minutes, a.status,
                       a.rejection_reason, a.created_at
                  from integration.attendance_record a
                  join integration.hr_employee e on e.employee_id = a.employee_id
                 where 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (businessDate != null) {
            sql.append(" and a.business_date = ?");
            params.add(Date.valueOf(businessDate));
        }
        if (employeeId != null) {
            sql.append(" and a.employee_id = ?");
            params.add(employeeId);
        }
        sql.append(" order by a.business_date desc, a.clock_in desc");

        return jdbc.query(sql.toString(), ATTENDANCE_MAPPER, params.toArray());
    }

    @Override
    public Optional<AttendanceRecordView> getAttendance(UUID attendanceId, ScopeContext scope) {
        if (attendanceId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                select a.attendance_id, a.owner_entity_id, a.external_record_id, a.employee_id,
                       e.employee_no, e.full_name, a.device_identifier, a.source_provider,
                       a.business_date, a.clock_in, a.clock_out, a.worked_minutes, a.status,
                       a.rejection_reason, a.created_at
                  from integration.attendance_record a
                  join integration.hr_employee e on e.employee_id = a.employee_id
                 where a.attendance_id = ?
                """,
                        ATTENDANCE_MAPPER,
                        attendanceId)
                .stream()
                .findFirst();
    }

    @Override
    public List<OvertimeCalculationView> listOvertime(String status, LocalDate businessDate, ScopeContext scope) {
        StringBuilder sql = new StringBuilder(
                """
                select c.calculation_id, c.attendance_id, c.owner_entity_id, c.employee_id,
                       e.employee_no, e.full_name, c.business_date, c.worked_minutes,
                       c.standard_minutes, c.qualifying_ot_minutes, c.calculated_ot_minutes,
                       c.ot_multiplier, c.calculated_amount, c.status, c.created_at
                  from integration.overtime_calculation c
                  join integration.hr_employee e on e.employee_id = c.employee_id
                 where 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" and c.status = ?");
            params.add(status.trim());
        }
        if (businessDate != null) {
            sql.append(" and c.business_date = ?");
            params.add(Date.valueOf(businessDate));
        }
        sql.append(" order by c.business_date desc, c.created_at desc");

        return jdbc.query(sql.toString(), OVERTIME_MAPPER, params.toArray());
    }

    @Override
    public Optional<OvertimeCalculationView> getOvertime(UUID calculationId, ScopeContext scope) {
        if (calculationId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                select c.calculation_id, c.attendance_id, c.owner_entity_id, c.employee_id,
                       e.employee_no, e.full_name, c.business_date, c.worked_minutes,
                       c.standard_minutes, c.qualifying_ot_minutes, c.calculated_ot_minutes,
                       c.ot_multiplier, c.calculated_amount, c.status, c.created_at
                  from integration.overtime_calculation c
                  join integration.hr_employee e on e.employee_id = c.employee_id
                 where c.calculation_id = ?
                """,
                        OVERTIME_MAPPER,
                        calculationId)
                .stream()
                .findFirst();
    }
}
