package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A customer was reactivated (CUS-07). */
public record CustomerReactivated(UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "customer.reactivated.v1";
}
