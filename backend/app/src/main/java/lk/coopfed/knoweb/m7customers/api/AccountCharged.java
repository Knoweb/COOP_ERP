package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A till's account tender was charged to a customer's account. Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record AccountCharged(
        UUID accountId,
        UUID customerId,
        UUID ownerEntityId,
        UUID postingId,
        UUID documentId,
        BigDecimal amount,
        BigDecimal balance,
        boolean limitBreached,
        boolean offline)
        implements DomainEvent {

    public static final String TYPE = "account.charged.v1";
}
