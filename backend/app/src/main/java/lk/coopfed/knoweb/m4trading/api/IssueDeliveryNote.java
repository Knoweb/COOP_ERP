package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** IssueDeliveryNote (24A section 6): the seller issues its draft note from its ENTITY series (24B). */
public record IssueDeliveryNote(UUID deliveryNoteId) {}
