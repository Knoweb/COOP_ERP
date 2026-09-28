package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** Deactivate a customer (27A section 6): refused while an open account has a balance. */
public record DeactivateCustomer(UUID customerId, String reason) {}
