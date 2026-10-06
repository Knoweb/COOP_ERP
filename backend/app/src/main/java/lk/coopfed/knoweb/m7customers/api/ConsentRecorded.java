package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A customer's consent was recorded. Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record ConsentRecorded(UUID customerId, UUID ownerEntityId, String purpose) implements DomainEvent {

    public static final String TYPE = "consent.recorded.v1";
}
