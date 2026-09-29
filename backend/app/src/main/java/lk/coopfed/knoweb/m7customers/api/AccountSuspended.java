package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** An account was suspended: the till refuses its tenders once its snapshot has it (27A section 7.3). */
public record AccountSuspended(UUID accountId, UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "account.suspended.v1";
}
