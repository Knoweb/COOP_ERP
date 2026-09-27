package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** invoice.printed.v1 (M4-11): the A4 copy of an issued invoice is in the object store. */
public record InvoicePrinted(UUID invoiceId, UUID sellerEntityId, String objectKey) implements DomainEvent {

    public static final String TYPE = "invoice.printed.v1";
}
