package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** credit_note.printed.v1 (M4-11): the A4 copy of an issued credit note is in the object store. */
public record CreditNotePrinted(UUID creditNoteId, UUID sellerEntityId, String objectKey) implements DomainEvent {

    public static final String TYPE = "credit_note.printed.v1";
}
