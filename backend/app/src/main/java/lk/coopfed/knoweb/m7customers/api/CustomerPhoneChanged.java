package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A customer's primary phone was changed. Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record CustomerPhoneChanged(UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "customer.phone_changed.v1";
}
