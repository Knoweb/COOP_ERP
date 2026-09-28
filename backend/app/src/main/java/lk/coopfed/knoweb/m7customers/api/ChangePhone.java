package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** A customer's new primary phone (27A section 6, ChangePhone); the old number's row is closed. */
public record ChangePhone(UUID customerId, String newPhone, String reason, boolean confirmedIdentity) {}
