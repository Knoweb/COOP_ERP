package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * A batch from a till was ingested (doc 19 section 6.4, sync.batch_received): how far the
 * device's cursor now stands and what became of the new events. Published once per batch, in
 * the transaction that completes it; a replay that applies nothing publishes nothing.
 */
public record SyncBatchReceived(
        UUID batchId,
        UUID deviceId,
        long firstSeq,
        long lastSeq,
        long lastAppliedSeq,
        int applied,
        int duplicates,
        int quarantined)
        implements DomainEvent {

    public static final String TYPE = "sync.batch_received.v1";
}
