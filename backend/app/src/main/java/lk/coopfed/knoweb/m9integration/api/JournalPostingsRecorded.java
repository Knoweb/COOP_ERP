package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** journal.postings_recorded.v1: the postings of a document are held for the entity's next export. */
public record JournalPostingsRecorded(UUID documentId, String docTypeCode, int postings) implements DomainEvent {

    public static final String TYPE = "journal.postings_recorded.v1";
}
