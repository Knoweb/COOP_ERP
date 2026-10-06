package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A suspended account was opened again. */
public record AccountReinstated(UUID accountId, UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "account.reinstated.v1";
}
