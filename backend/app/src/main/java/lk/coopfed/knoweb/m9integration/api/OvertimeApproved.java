package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * integration.overtime_approved.v1: overtime was approved and recorded as a payroll contribution.
 */
public record OvertimeApproved(
        UUID contributionId,
        UUID calculationId,
        UUID employeeId,
        String payrollPeriod,
        int approvedOtMinutes,
        BigDecimal approvedAmount,
        UUID approvedBy,
        Instant approvedAt)
        implements DomainEvent {

    public static final String TYPE = "integration.overtime_approved.v1";
}
