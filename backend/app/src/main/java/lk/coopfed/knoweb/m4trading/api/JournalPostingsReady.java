package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** journal.postings_ready.v1 (doc 24 section 5.3): the journal lines of an issued document, for M9's export. */
public record JournalPostingsReady(
        UUID documentId, String docTypeCode, String docNumberDisplay, UUID ownerEntityId, List<Posting> postings)
        implements DomainEvent {

    public static final String TYPE = "journal.postings_ready.v1";
}
