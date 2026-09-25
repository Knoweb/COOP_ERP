package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.Claim;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.Claimed;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.Completed;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.CursorBusy;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.Gap;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.InFlight;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.Replay;
import lk.coopfed.knoweb.kernel.internal.sync.IngestTransactions.StoredAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ingests one batch from a till (doc 32 section 3.3; 19A section 8, BatchIngestor.ingest). The
 * device has been authenticated and found ACTIVE by the time this runs (DeviceAuth); what is
 * left is, in the order of the document:
 *
 * <pre>
 *   1  the batch's shape and limits (DR-1), the application floor (426)
 *   2  claim the cursor: one batch in flight per device on any instance (409)
 *   3  compare with the cursor: the same batch again (its stored acknowledgement), a gap (409 with
 *      expected_seq), a replay (answered from state), or new events to apply
 *   4  the new events, sync.ingest.chunk_size per transaction, in sequence order: each applied
 *   6  or quarantined (EventApplier); the cursor moves with each committed chunk
 *   7  release the cursor, store the acknowledgement
 *   8  answer with it
 * </pre>
 *
 * Step 5, business validation with the shared engine, is the owning module's: its consumer of the
 * event re-resolves prices against the snapshot version the till reported and flags, never
 * refuses (S4). The acknowledgement therefore says APPLIED, DUPLICATE or QUARANTINED; FLAGGED
 * reaches the device later, through what the module publishes.
 */
@Component
class BatchIngestor {

    private static final Logger log = LoggerFactory.getLogger(BatchIngestor.class);

    /** One batch as the controller hands it over; wireBytes is its size as sent, compressed or not. */
    record BatchInput(
            UUID batchId,
            long firstSeq,
            long lastSeq,
            String appVersion,
            Long snapshotVersionInUse,
            Instant deviceClock,
            List<JsonNode> events,
            long wireBytes) {}

    private final IngestTransactions transactions;
    private final SyncSettings settings;
    private final Clock clock;

    BatchIngestor(IngestTransactions transactions, SyncSettings settings, Clock clock) {
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
    }

    Ack ingest(ScopeContext device, DeviceRecord record, BatchInput batch) {
        checkShape(device, batch);

        Claim claim;
        try {
            claim = transactions.claim(
                    device,
                    batch.batchId(),
                    batch.firstSeq(),
                    batch.lastSeq(),
                    batch.snapshotVersionInUse(),
                    batch.appVersion(),
                    settings.inFlightTimeout(device),
                    settings.gapAnomalyAfter(device));
        } catch (CursorBusy busy) {
            throw inFlight(transactions.inFlightBatch(device));
        }

        return switch (claim) {
            case StoredAck stored -> {
                Completed now = transactions.current(device);
                yield ack(
                        batch, stored.stored().lastAppliedSeq(), stored.stored().outcomes(), now);
            }
            case InFlight inFlight -> throw inFlight(inFlight.batchId());
            case Gap gap -> throw new ProblemException("sync.sequence_gap", Map.of("expected_seq", gap.expectedSeq()));
            case Replay replay -> {
                List<Ack.Outcome> outcomes = transactions.fromState(device, batch.firstSeq(), batch.lastSeq());
                yield ack(batch, replay.lastAppliedSeq(), outcomes, transactions.current(device));
            }
            case Claimed claimed -> applyNewEvents(device, record, batch, claimed.lastAppliedSeq());
        };
    }

    private Ack applyNewEvents(ScopeContext device, DeviceRecord record, BatchInput batch, long lastApplied) {
        List<Ack.Outcome> outcomes = new ArrayList<>(batch.events().size());
        int duplicates = 0;
        int applied = 0;
        int quarantined = 0;
        try {
            // The overlap with what is already at central: answered from state, not applied again.
            if (batch.firstSeq() <= lastApplied) {
                List<Ack.Outcome> known = transactions.fromState(device, batch.firstSeq(), lastApplied);
                outcomes.addAll(known);
                duplicates = known.size();
            }
            int chunkSize = settings.chunkSize(device);
            int maxEventBytes = settings.maxEventBytes(device);
            int start = (int) (lastApplied + 1 - batch.firstSeq());
            int chunkIndex = 0;
            for (int from = start; from < batch.events().size(); from += chunkSize) {
                List<JsonNode> chunk = batch.events()
                        .subList(from, Math.min(from + chunkSize, batch.events().size()));
                IngestTransactions.ChunkResult result = transactions.applyChunk(
                        device, record, batch.batchId(), batch.firstSeq() + from, chunk, chunkIndex++, maxEventBytes);
                for (Ack.Outcome outcome : result.outcomes()) {
                    outcomes.add(outcome);
                    if (Ack.QUARANTINED.equals(outcome.outcome())) {
                        quarantined++;
                    } else {
                        applied++;
                    }
                }
            }
            long last = batch.lastSeq();
            Completed completed = transactions.complete(
                    device,
                    batch.batchId(),
                    batch.firstSeq(),
                    batch.lastSeq(),
                    new Ack.Stored(last, outcomes),
                    applied,
                    duplicates,
                    quarantined);
            return ack(batch, completed.lastAppliedSeq(), outcomes, completed);
        } catch (IngestTransactions.ClaimLost lost) {
            throw inFlight(lost.holder);
        } catch (RuntimeException failure) {
            // The cursor stands at the last committed chunk; the till resends from there with a new
            // batch id and the overlap is answered DUPLICATE (doc 32 section 3.4, partial success).
            try {
                transactions.release(device, batch.batchId());
            } catch (RuntimeException alsoFailed) {
                log.warn(
                        "Batch {} of device {} failed and its claim could not be released; it expires by itself",
                        batch.batchId(),
                        device.deviceId());
            }
            throw failure;
        }
    }

    private void checkShape(ScopeContext device, BatchInput batch) {
        long count = batch.lastSeq() - batch.firstSeq() + 1;
        if (batch.lastSeq() < batch.firstSeq() || count != batch.events().size()) {
            throw new ProblemException(
                    "sync.batch_inconsistent",
                    Map.of(
                            "first_seq",
                            batch.firstSeq(),
                            "last_seq",
                            batch.lastSeq(),
                            "events",
                            batch.events().size()));
        }
        int maxEvents = settings.maxEvents(device);
        long maxBytes = settings.maxBatchBytes(device);
        if (count > maxEvents || batch.wireBytes() > maxBytes) {
            throw new ProblemException("sync.batch_too_large", Map.of("max_events", maxEvents, "max_bytes", maxBytes));
        }
        String floor = settings.appVersionFloor(device);
        if (!AppVersions.atLeast(batch.appVersion(), floor)) {
            throw new ProblemException("sync.app_below_floor", Map.of("floor", floor));
        }
    }

    private Ack ack(BatchInput batch, long lastAppliedSeq, List<Ack.Outcome> outcomes, Completed state) {
        Instant now = clock.instant();
        Long offset = batch.deviceClock() == null
                ? null
                : Duration.between(batch.deviceClock(), now).toMillis();
        List<Ack.Instruction> instructions =
                state.resendFrom() == null ? List.of() : List.of(Ack.Instruction.resendFrom(state.resendFrom()));
        return new Ack(batch.batchId(), lastAppliedSeq, outcomes, now, offset, state.snapshotVersion(), instructions);
    }

    private static ProblemException inFlight(UUID holder) {
        return new ProblemException(
                "sync.batch_in_flight", holder == null ? Map.of() : Map.of("in_flight_batch_id", holder.toString()));
    }
}
