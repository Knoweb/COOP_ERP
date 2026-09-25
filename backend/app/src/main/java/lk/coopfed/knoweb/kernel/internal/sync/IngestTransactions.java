package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import lk.coopfed.knoweb.kernel.api.SyncBatchReceived;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactions of one batch (doc 32 section 3.3; 19A section 8), each a public
 * {@code @Transactional} method taking the device's {@link ScopeContext}, which is what puts the
 * device's scope on the connection. {@link BatchIngestor} calls them in order: claim the cursor,
 * apply the new events a chunk at a time, complete. The cursor row is the only state (S7): a
 * batch that starts on one instance can be resent to another, which answers the same way.
 */
@Component
public class IngestTransactions {

    static final String AUDIT_ANOMALY = "SYNC_ANOMALY";

    /** What the claim found. */
    sealed interface Claim {}

    /** The same batch as the last completed one: answer with its stored acknowledgement. */
    record StoredAck(Ack.Stored stored) implements Claim {}

    /** Another batch of this device is being ingested. */
    record InFlight(UUID batchId) implements Claim {}

    /** The batch starts above the cursor: 409 with the sequence to resend from. */
    record Gap(long expectedSeq) implements Claim {}

    /** Everything in the batch is at or below the cursor: answer from state, apply nothing. */
    record Replay(long lastAppliedSeq) implements Claim {}

    /** The batch holds the cursor now; events above {@code lastAppliedSeq} are to be applied. */
    record Claimed(long lastAppliedSeq) implements Claim {}

    /** The cursor after a chunk. */
    record ChunkResult(long lastAppliedSeq, List<Ack.Outcome> outcomes) {}

    /** The cursor after the batch: resendFrom is set while central waits for a resend. */
    record Completed(long lastAppliedSeq, Long resendFrom, long snapshotVersion) {}

    /** The batch lost the cursor to another claim (its own was older than the in-flight timeout). */
    static final class ClaimLost extends RuntimeException {
        final UUID holder;

        ClaimLost(UUID holder) {
            super("The cursor is held by batch " + holder, null, false, false);
            this.holder = holder;
        }
    }

    private final JdbcTemplate jdbc;
    private final EventApplier applier;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<IngestionProbe> probe;

    IngestTransactions(
            JdbcTemplate jdbc,
            EventApplier applier,
            AuditFacade audit,
            EventPublisher events,
            ObjectMapper json,
            Clock clock,
            ObjectProvider<IngestionProbe> probe) {
        this.jdbc = jdbc;
        this.applier = applier;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.probe = probe;
    }

    /**
     * Step 2 and 3 of doc 32 section 3.3: lock the cursor ({@code NOWAIT}: a second batch meets a
     * lock and is told so at once rather than waiting behind the first) and compare.
     */
    @Transactional
    public Claim claim(
            ScopeContext device,
            UUID batchId,
            long firstSeq,
            long lastSeq,
            Long snapshotVersionInUse,
            String appVersion,
            Duration inFlightTimeout,
            int gapAnomalyAfter) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    """
                    select last_applied_seq, last_batch_id, last_ack::text as last_ack, in_flight_batch_id,
                           in_flight_since, gap_rejections
                      from kernel.device_sync_cursor
                     where device_id = ?
                       for update nowait
                    """,
                    device.deviceId());
        } catch (DataAccessException failure) {
            // Spring does not translate PostgreSQL's 55P03 for every driver version; the state is
            // what says it.
            if (lockNotAvailable(failure)) {
                throw new CursorBusy();
            }
            throw failure;
        }
        if (rows.isEmpty()) {
            throw new ProblemException("sync.device_not_enrolled");
        }
        Map<String, Object> cursor = rows.getFirst();
        long lastApplied = ((Number) cursor.get("last_applied_seq")).longValue();
        Instant now = clock.instant();

        if (batchId.equals(cursor.get("last_batch_id")) && cursor.get("last_ack") != null) {
            return new StoredAck(read((String) cursor.get("last_ack")));
        }
        UUID inFlight = (UUID) cursor.get("in_flight_batch_id");
        Timestamp since = (Timestamp) cursor.get("in_flight_since");
        if (inFlight != null && since != null && since.toInstant().isAfter(now.minus(inFlightTimeout))) {
            return new InFlight(inFlight);
        }
        if (firstSeq > lastApplied + 1) {
            int rejections = ((Number) cursor.get("gap_rejections")).intValue() + 1;
            jdbc.update(
                    "update kernel.device_sync_cursor set gap_rejections = ? where device_id = ?",
                    rejections,
                    device.deviceId());
            if (rejections == gapAnomalyAfter) {
                // doc 32 section 7: "Repeated 409 for the same device raises SYNC_ANOMALY", once per run.
                UUID anomaly = Ids.next();
                audit.record(
                        AUDIT_ANOMALY,
                        Subject.of("device", device.deviceId()),
                        null,
                        Map.of("expectedSeq", lastApplied + 1, "firstSeq", firstSeq, "rejections", rejections),
                        device,
                        "Repeated sequence gaps");
                events.publish(new SyncAnomaly(anomaly, device.deviceId(), "REPEATED_GAP", lastApplied + 1, null));
            }
            return new Gap(lastApplied + 1);
        }
        if (lastSeq <= lastApplied) {
            return new Replay(lastApplied);
        }
        jdbc.update(
                """
                update kernel.device_sync_cursor
                   set in_flight_batch_id = ?, in_flight_since = ?, gap_rejections = 0,
                       snapshot_version_reported = coalesce(?, snapshot_version_reported), app_version = ?
                 where device_id = ?
                """,
                batchId,
                Timestamp.from(now),
                snapshotVersionInUse,
                appVersion,
                device.deviceId());
        return new Claimed(lastApplied);
    }

    /** PostgreSQL 55P03, lock_not_available: what NOWAIT answers when another transaction holds the row. */
    static boolean lockNotAvailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** The cursor is locked by a transaction of another batch right now. */
    static final class CursorBusy extends RuntimeException {
        CursorBusy() {
            super("The device's cursor is locked by another ingestion", null, false, false);
        }
    }

    /** Which batch holds the cursor, read without a lock, for the 409 of a busy cursor. */
    @Transactional(readOnly = true)
    public UUID inFlightBatch(ScopeContext device) {
        List<UUID> found = jdbc.queryForList(
                "select in_flight_batch_id from kernel.device_sync_cursor where device_id = ?",
                UUID.class,
                device.deviceId());
        return found.isEmpty() ? null : found.getFirst();
    }

    /** What the ledger says of sequences at or below the cursor. */
    @Transactional(readOnly = true)
    public List<Ack.Outcome> fromState(ScopeContext device, long fromSeq, long toSeq) {
        if (toSeq < fromSeq) {
            return List.of();
        }
        return applier.fromState(device.deviceId(), fromSeq, toSeq);
    }

    /**
     * Step 4 to 7 for one chunk: the events in order, then the cursor, in one transaction. A crash
     * before the commit leaves the cursor at the last committed chunk (doc 32 section 3.3 step 7).
     */
    @Transactional
    public ChunkResult applyChunk(
            ScopeContext device,
            DeviceRecord record,
            UUID batchId,
            long firstSeq,
            List<JsonNode> chunk,
            int chunkIndex,
            int maxEventBytes) {
        Map<String, Object> cursor = jdbc.queryForMap(
                "select last_applied_seq, in_flight_batch_id from kernel.device_sync_cursor where device_id = ? for update",
                device.deviceId());
        if (!batchId.equals(cursor.get("in_flight_batch_id"))) {
            throw new ClaimLost((UUID) cursor.get("in_flight_batch_id"));
        }
        long lastApplied = ((Number) cursor.get("last_applied_seq")).longValue();
        if (lastApplied != firstSeq - 1) {
            throw new IllegalStateException(
                    "The cursor of " + device.deviceId() + " moved under batch " + batchId + ": " + lastApplied);
        }
        List<Ack.Outcome> outcomes = new ArrayList<>(chunk.size());
        long seq = firstSeq;
        for (JsonNode event : chunk) {
            outcomes.add(applier.apply(device, record, batchId, seq, event, maxEventBytes));
            seq++;
        }
        long last = seq - 1;
        jdbc.update(
                """
                update kernel.device_sync_cursor
                   set last_applied_seq = ?, last_applied_at = ?, in_flight_since = ?
                 where device_id = ?
                """,
                last,
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
                device.deviceId());
        probe.ifAvailable(p -> p.beforeChunkCommit(device.deviceId(), chunkIndex));
        return new ChunkResult(last, outcomes);
    }

    /**
     * Step 7 and 8: release the cursor, store the acknowledgement for a resend of the same batch,
     * and publish what the batch did.
     */
    @Transactional
    public Completed complete(
            ScopeContext device,
            UUID batchId,
            long firstSeq,
            long lastSeq,
            Ack.Stored stored,
            int applied,
            int duplicates,
            int quarantined) {
        Map<String, Object> cursor = jdbc.queryForMap(
                """
                update kernel.device_sync_cursor
                   set last_batch_id = ?, last_ack = cast(? as jsonb), last_ack_at = ?,
                       in_flight_batch_id = null, in_flight_since = null,
                       resend_from_seq = case when resend_from_seq is not null and last_applied_seq >= resend_from_seq
                                              then null else resend_from_seq end
                 where device_id = ? and in_flight_batch_id = ?
                returning last_applied_seq, resend_from_seq
                """,
                batchId,
                write(stored),
                Timestamp.from(clock.instant()),
                device.deviceId(),
                batchId);
        if (applied + quarantined > 0) {
            events.publish(new SyncBatchReceived(
                    batchId,
                    device.deviceId(),
                    firstSeq,
                    lastSeq,
                    ((Number) cursor.get("last_applied_seq")).longValue(),
                    applied,
                    duplicates,
                    quarantined));
        }
        Number resendFrom = (Number) cursor.get("resend_from_seq");
        return new Completed(
                ((Number) cursor.get("last_applied_seq")).longValue(),
                resendFrom == null ? null : resendFrom.longValue(),
                snapshotVersion(device));
    }

    /** After a failure: let the next batch in without waiting for the in-flight timeout. */
    @Transactional
    public void release(ScopeContext device, UUID batchId) {
        jdbc.update(
                """
                update kernel.device_sync_cursor set in_flight_batch_id = null, in_flight_since = null
                 where device_id = ? and in_flight_batch_id = ?
                """,
                device.deviceId(),
                batchId);
    }

    /** The cursor and the location's snapshot version, for an answer that applies nothing. */
    @Transactional(readOnly = true)
    public Completed current(ScopeContext device) {
        Map<String, Object> cursor = jdbc.queryForMap(
                "select last_applied_seq, resend_from_seq from kernel.device_sync_cursor where device_id = ?",
                device.deviceId());
        Number resendFrom = (Number) cursor.get("resend_from_seq");
        return new Completed(
                ((Number) cursor.get("last_applied_seq")).longValue(),
                resendFrom == null ? null : resendFrom.longValue(),
                snapshotVersion(device));
    }

    long snapshotVersion(ScopeContext device) {
        if (device.locationId() == null) {
            return 0;
        }
        List<Long> found = jdbc.queryForList(
                "select current_version from kernel.location_snapshot_version where location_id = ?",
                Long.class,
                device.locationId());
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private String write(Ack.Stored stored) {
        try {
            return json.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The acknowledgement could not be stored", e);
        }
    }

    private Ack.Stored read(String stored) {
        try {
            return json.readValue(stored, Ack.Stored.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The stored acknowledgement could not be read", e);
        }
    }
}
