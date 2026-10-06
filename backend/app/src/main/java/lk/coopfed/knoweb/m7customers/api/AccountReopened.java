package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A CLOSED account was reopened as SUSPENDED, to settle what a till posted on it (CR-27A-1 item 1). */
public record AccountReopened(UUID accountId, UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "account.reopened.v1";
}
