package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A customer's NIC was recorded again from the card presented. Ids only: never the number, its hash or its last four. */
public record CustomerNicRecaptured(UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "customer.nic_recaptured.v1";
}
