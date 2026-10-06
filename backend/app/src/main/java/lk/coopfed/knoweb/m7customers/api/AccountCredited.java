package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A till's refund was credited to a customer's account. Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record AccountCredited(
        UUID accountId,
        UUID customerId,
        UUID ownerEntityId,
        UUID postingId,
        UUID documentId,
        BigDecimal amount,
        BigDecimal balance)
        implements DomainEvent {

    public static final String TYPE = "account.credited.v1";
}
