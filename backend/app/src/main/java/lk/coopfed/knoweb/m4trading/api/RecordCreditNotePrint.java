package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * RecordCreditNotePrint (M4-11): the worker printed the seller's credit note to A4 and keeps where
 * the PDF is, as {@link RecordInvoicePrint} does for an invoice. Run by M4's print consumer in the
 * seller's scope, never over HTTP.
 */
public record RecordCreditNotePrint(UUID creditNoteId, String objectKey) {}
