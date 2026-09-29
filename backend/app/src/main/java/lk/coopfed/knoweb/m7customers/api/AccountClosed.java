package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** An account with nothing owed and nothing held was closed. */
public record AccountClosed(UUID accountId, UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "account.closed.v1";
}
