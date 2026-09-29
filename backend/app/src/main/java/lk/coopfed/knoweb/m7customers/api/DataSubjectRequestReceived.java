package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A data-subject request was received. Ids only. */
public record DataSubjectRequestReceived(UUID requestId, UUID customerId, UUID ownerEntityId, String kind)
        implements DomainEvent {

    public static final String TYPE = "dsar.received.v1";
}
