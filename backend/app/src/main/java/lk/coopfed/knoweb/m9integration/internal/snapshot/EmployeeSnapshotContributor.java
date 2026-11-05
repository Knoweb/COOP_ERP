package lk.coopfed.knoweb.m9integration.internal.snapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes active employee discount eligibility to till snapshots (PGL-M1-06).
 * Contains ONLY employee_no, active status, and optional branch location constraint.
 * Strictly excludes NIC, full name, EPF, hourly rates, attendance, and payroll amounts.
 */
@Component
public class EmployeeSnapshotContributor implements SnapshotContributor {

    public static final String EMPLOYEE_DISCOUNT_ELIGIBILITY = "employee_discount_eligibility";

    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeSnapshotContributor(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Set<String> tables() {
        return Set.of(EMPLOYEE_DISCOUNT_ELIGIBILITY);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (rowIds.isEmpty() || !EMPLOYEE_DISCOUNT_ELIGIBILITY.equals(table)) {
            return Map.of();
        }
        return employees(shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        if (!EMPLOYEE_DISCOUNT_ELIGIBILITY.equals(table)) {
            return Map.of();
        }
        return employees(shop, null);
    }

    private Map<UUID, Map<String, Object>> employees(Shop shop, Collection<UUID> employeeIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entity", shop.ownerEntityId())
                .addValue("shopLocationId", shop.locationId())
                .addValue("ids", employeeIds);
        String onlyIds = employeeIds == null ? "" : " and e.employee_id in (:ids)";
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.query(
                """
                select e.employee_id, e.employee_no, e.location_id, e.status
                  from integration.hr_employee e
                 where e.owner_entity_id = :entity
                   and e.status = 'ACTIVE'
                   and (e.location_id is null or e.location_id = :shopLocationId)
                """
                        + onlyIds
                        + " order by e.employee_id",
                params,
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("employee_no", rs.getString("employee_no"));
                    row.put("active", "ACTIVE".equals(rs.getString("status")));
                    UUID locId = rs.getObject("location_id", UUID.class);
                    row.put("location_id", locId != null ? locId.toString() : null);
                    rows.put(rs.getObject("employee_id", UUID.class), row);
                });
        return rows;
    }
}

