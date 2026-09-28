package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** ResolveInvoiceDispute (24A section 6): the seller or the buyer closes the open dispute of an invoice. */
public record ResolveInvoiceDispute(UUID invoiceId, String note) {}
