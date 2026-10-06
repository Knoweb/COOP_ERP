package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record DebitNotePrinted(UUID debitNoteId, UUID sellerEntityId, String objectKey) implements DomainEvent {
    public static final String TYPE = "debit_note.printed.v1";
}
