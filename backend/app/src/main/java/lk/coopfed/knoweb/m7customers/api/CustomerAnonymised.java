package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A customer's identity was erased (27A section 6.4): names, phones, NIC, attributes and tags gone,
 * consents withdrawn; the postings and documents kept for the retention period. Ids only.
 */
public record CustomerAnonymised(UUID customerId, UUID ownerEntityId, UUID requestId) implements DomainEvent {

    public static final String TYPE = "customer.anonymised.v1";
}
