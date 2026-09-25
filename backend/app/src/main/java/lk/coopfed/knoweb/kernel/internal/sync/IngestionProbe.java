package lk.coopfed.knoweb.kernel.internal.sync;

import java.util.UUID;

/**
 * A hook into ingestion at the one moment the conformance suite of doc 32 section 11 needs to
 * reach: inside a chunk's transaction, after its events and the cursor are written and before it
 * commits. "Crash mid-batch at central" throws here; "Concurrent batches for one device" holds
 * the first batch here while the second arrives. No bean implements it in the application, so
 * ingestion never calls anything; a test registers one.
 */
@FunctionalInterface
public interface IngestionProbe {

    void beforeChunkCommit(UUID deviceId, int chunkIndex);
}
