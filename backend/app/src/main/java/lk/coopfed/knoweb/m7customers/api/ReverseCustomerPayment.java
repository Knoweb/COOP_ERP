package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * ReverseCustomerPayment (27A section 6): a REVERSES-linked CPR undoes a repayment recorded in
 * error (the wrong customer, a bounced deposit), and the allocations it made are undone. MFA; a
 * reason.
 */
public record ReverseCustomerPayment(UUID documentId, String reason) {}
