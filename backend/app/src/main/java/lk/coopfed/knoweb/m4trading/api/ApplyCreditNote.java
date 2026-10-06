package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ApplyCreditNote (24A section 6; CR-24A-3 item 2): the seller's accounts apply money a credit note
 * holds unapplied (what was not due on the invoice it credits when it was issued) to an open,
 * undisputed invoice of the same buyer.
 *
 * @param amount what to apply; null applies as much as fits, the smaller of what the credit note
 *     holds unapplied and what is due on the invoice
 */
public record ApplyCreditNote(UUID creditNoteId, UUID invoiceId, BigDecimal amount) {}
