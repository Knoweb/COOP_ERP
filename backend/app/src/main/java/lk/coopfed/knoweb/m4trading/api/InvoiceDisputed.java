package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** invoice.disputed.v1 (24A section 6): the buyer disputes the seller's invoice. */
public record InvoiceDisputed(UUID invoiceId, UUID sellerEntityId, UUID buyerEntityId, String reason)
        implements DomainEvent {

    public static final String TYPE = "invoice.disputed.v1";
}
