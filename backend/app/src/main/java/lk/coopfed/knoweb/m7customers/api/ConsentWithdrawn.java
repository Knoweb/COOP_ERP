package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** Consent withdrawal event; contains no personal identifying details. */
public record ConsentWithdrawn(UUID customerId, UUID ownerEntityId, String purpose) implements DomainEvent {

    public static final String TYPE = "consent.withdrawn.v1";
}
