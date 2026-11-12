package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** Record a customer's consent after registration (CUS-04). */
public record RecordConsent(UUID customerId, String purpose, String via) {}
