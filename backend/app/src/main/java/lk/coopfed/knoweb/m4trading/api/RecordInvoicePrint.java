package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * RecordInvoicePrint (M4-11): the worker printed the seller's issued invoice to A4 and keeps where
 * the PDF is, so the invoice screen's Print button can hand out a link to it. Run by M4's print
 * consumer in the seller's scope, never over HTTP.
 *
 * @param objectKey the object store key of the PDF, as {@code A4Renderer.Rendered} gives it
 */
public record RecordInvoicePrint(UUID invoiceId, String objectKey) {}
