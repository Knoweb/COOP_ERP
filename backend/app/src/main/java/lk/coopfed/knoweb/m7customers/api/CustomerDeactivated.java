package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A customer was deactivated. Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record CustomerDeactivated(UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "customer.deactivated.v1";
}
