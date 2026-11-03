package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** Reactivate a deactivated customer (CUS-07). */
public record ReactivateCustomer(UUID customerId, String reason) {}
