package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Published queries for HR employee profiles.
 */
public interface EmployeeQueries {

    Optional<EmployeeView> lookupByEmployeeNo(String employeeNo, ScopeContext scope);

    Optional<EmployeeView> getEmployee(UUID employeeId, ScopeContext scope);

    List<EmployeeView> listEmployees(ScopeContext scope);

    record EmployeeView(
            UUID employeeId,
            UUID ownerEntityId,
            String employeeNo,
            String fullName,
            String nic,
            String epfNo,
            BigDecimal hourlyRate,
            UUID locationId,
            String status,
            Instant createdAt,
            Instant updatedAt) {}
}
