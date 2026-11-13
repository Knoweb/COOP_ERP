package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** Withdraw active consent without deleting its history (CUS-04). */
public record WithdrawConsent(UUID customerId, String purpose) {}
