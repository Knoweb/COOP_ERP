package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Register or update an employee profile for biometric attendance tracking and payroll contributions.
 */
public record RegisterEmployee(
        String employeeNo, String fullName, String nic, String epfNo, BigDecimal hourlyRate, UUID locationId) {}
