package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** invoice.dispute_resolved.v1 (24A section 6): the open dispute of an invoice is closed, by either party. */
public record InvoiceDisputeResolved(UUID invoiceId, UUID sellerEntityId, UUID buyerEntityId, UUID resolvedByEntityId)
        implements DomainEvent {

    public static final String TYPE = "invoice.dispute_resolved.v1";
}
