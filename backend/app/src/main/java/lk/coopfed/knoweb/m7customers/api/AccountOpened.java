package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A society opened a credit account for a customer. Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record AccountOpened(UUID accountId, UUID customerId, UUID ownerEntityId, BigDecimal creditLimit)
        implements DomainEvent {

    public static final String TYPE = "account.opened.v1";
}
