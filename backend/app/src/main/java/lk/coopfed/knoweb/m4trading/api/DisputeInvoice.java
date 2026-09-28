package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** DisputeInvoice (24A section 6): the buyer disputes an invoice it received, with a reason. */
public record DisputeInvoice(UUID invoiceId, String reason) {}
