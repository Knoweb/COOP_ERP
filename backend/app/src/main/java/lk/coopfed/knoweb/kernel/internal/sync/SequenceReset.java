package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncSequenceReset;
import lk.coopfed.knoweb.kernel.internal.security.PermissionGate;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sequence reset of doc 32 section 8: "only by an MPCS administrator with a reason code, on a
 * device that cannot produce expected_seq; central records the gap range, sets the cursor to the
 * device's new start, raises ALERT". It is what ends a device's run of 409 sync.sequence_gap
 * answers when the rows central asks for no longer exist on the till (its outbox was lost or
 * corrupted, doc 32 section 7): the numbers between the cursor and the device's new start become
 * a documented gap, never received and never hidden (doc 18 E-I3).
 *
 * <p>Only forward. The cursor never goes back: a device whose sequence restarted below the cursor
 * (a factory-reset till) is enrolled again instead, and enrolment hands it the next sequence
 * central expects (doc 32 section 8; {@link EnrolmentStore}). Going back would make central answer
 * the device's new events as duplicates of the old ones at the same numbers.
 *
 * <p>The rule of a command handler, as the kernel's other operations follow it
 * ({@link EnrolmentStore}): the guards, the mutation, {@code audit.record}, {@code events.publish},
 * in one transaction. A retry with the same Idempotency-Key is answered with the gap it recorded.
 */
@Component
public class SequenceReset {

    static final String AUDIT_RESET = "SYNC_SEQUENCE_RESET";

    /** Sequence reset is part of enrolling a device and giving it its lane, so it asks for the same permission. */
    static final String PERMISSION = EnrolmentStore.PERMISSION;

    /** The gap recorded; the device's next batch starts at {@code toSeq + 1}. */
    record Gap(UUID gapId, UUID deviceId, long fromSeq, long toSeq, Instant recordedAt) {}

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PermissionGate gate;
    private final Clock clock;

    SequenceReset(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, PermissionGate gate, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.gate = gate;
        this.clock = clock;
    }

    /**
     * @param device          M1's device, read by the caller outside the transaction; null when
     *                        there is none
     * @param newStartSeq     the first sequence the device will send from now on
     * @param inFlightTimeout after how long a batch marked in flight is a dead instance's
     *                        ({@code sync.batch.in_flight_timeout})
     * @throws ProblemException {@code permission.denied}, {@code mfa.required},
     *                          {@code sync.sequence_reset.device_not_enrolled},
     *                          {@code sync.batch_in_flight}, {@code sync.sequence_reset.not_forward}
     */
    @Transactional
    public Gap reset(
            ScopeContext ctx,
            DeviceRecord device,
            long newStartSeq,
            String reasonCode,
            String reasonText,
            String idempotencyKey,
            Duration inFlightTimeout) {
        // 1. an administrator of the device's entity, with the permission (and its second factor).
        if (ctx.userId() == null || ctx.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("permission.denied", Map.of("permission", PERMISSION));
        }
        gate.require(ctx, PERMISSION);
        // 2. the device is M1's, of the caller's entity, and enrolled for sync (it has a cursor).
        if (device == null || !device.ownerEntityId().equals(ctx.entityId())) {
            throw new ProblemException("sync.sequence_reset.device_not_enrolled");
        }
        Map<String, Object> cursor = lockedCursor(device.deviceId());
        if (cursor == null) {
            throw new ProblemException("sync.sequence_reset.device_not_enrolled");
        }
        // 3. a retry of a reset already made is answered with it.
        List<Gap> earlier = jdbc.query(
                """
                select gap_id, device_id, from_seq, to_seq, recorded_at from kernel.sync_sequence_gap
                 where device_id = ? and idempotency_key = ?
                """,
                (rs, n) -> new Gap(
                        rs.getObject("gap_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        rs.getLong("from_seq"),
                        rs.getLong("to_seq"),
                        rs.getTimestamp("recorded_at").toInstant()),
                device.deviceId(),
                idempotencyKey);
        if (!earlier.isEmpty()) {
            return earlier.getFirst();
        }
        // 4. no batch of the device is being ingested: the cursor would move under it.
        Instant now = clock.instant();
        UUID inFlight = (UUID) cursor.get("in_flight_batch_id");
        Timestamp since = (Timestamp) cursor.get("in_flight_since");
        if (inFlight != null && since != null && since.toInstant().isAfter(now.minus(inFlightTimeout))) {
            throw new ProblemException("sync.batch_in_flight", Map.of("in_flight_batch_id", inFlight.toString()));
        }
        // 5. forward only, and past at least one number: the device cannot produce expected_seq.
        long lastApplied = ((Number) cursor.get("last_applied_seq")).longValue();
        long expected = lastApplied + 1;
        if (newStartSeq <= expected) {
            throw new ProblemException(
                    "sync.sequence_reset.not_forward", Map.of("expected_seq", expected, "new_start_seq", newStartSeq));
        }

        UUID gapId = Ids.next();
        long toSeq = newStartSeq - 1;
        jdbc.update(
                """
                insert into kernel.sync_sequence_gap (gap_id, device_id, owner_entity_id, from_seq, to_seq, reason_code,
                                                      reason_text, recorded_by_user_id, idempotency_key, recorded_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                gapId,
                device.deviceId(),
                device.ownerEntityId(),
                expected,
                toSeq,
                reasonCode,
                reasonText,
                ctx.userId(),
                idempotencyKey,
                Timestamp.from(now));
        jdbc.update(
                """
                update kernel.device_sync_cursor
                   set last_applied_seq = ?, last_applied_at = ?, gap_rejections = 0
                 where device_id = ?
                """,
                toSeq,
                Timestamp.from(now),
                device.deviceId());

        Map<String, Object> before = Map.of("lastAppliedSeq", lastApplied);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("lastAppliedSeq", toSeq);
        after.put("gapFromSeq", expected);
        after.put("gapToSeq", toSeq);
        after.put("reasonCode", reasonCode);
        String reason = reasonText == null || reasonText.isBlank() ? reasonCode : reasonCode + ": " + reasonText;
        audit.record(AUDIT_RESET, Subject.of("device", device.deviceId()), before, after, ctx, reason);
        events.publish(new SyncSequenceReset(gapId, device.deviceId(), expected, toSeq, reasonCode));
        return new Gap(gapId, device.deviceId(), expected, toSeq, now);
    }

    /** The cursor row, locked for this transaction; null when the device was never enrolled. */
    private Map<String, Object> lockedCursor(UUID deviceId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    """
                    select last_applied_seq, in_flight_batch_id, in_flight_since
                      from kernel.device_sync_cursor
                     where device_id = ?
                       for update nowait
                    """,
                    deviceId);
        } catch (DataAccessException failure) {
            if (IngestTransactions.lockNotAvailable(failure)) {
                // A batch holds the row right now.
                throw new ProblemException("sync.batch_in_flight");
            }
            throw failure;
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
