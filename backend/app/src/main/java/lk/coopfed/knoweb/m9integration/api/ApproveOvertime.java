package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;

/**
 * Approve calculated overtime and promote to payroll contribution stage.
 */
public record ApproveOvertime(UUID calculationId, String payrollPeriod, String notes) {}
