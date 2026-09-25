package lk.coopfed.knoweb.kernel.internal.sync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The acknowledgement of a batch (doc 32 section 3.4). {@link #lastAppliedSeq} is authoritative:
 * everything at or below it is durably at central. The controller turns it into the slice's
 * SyncAck; the outcomes and the sequence are also stored on the cursor, so that a resend of the
 * same batch is answered with the same acknowledgement (19A section 8).
 */
record Ack(
        UUID batchId,
        long lastAppliedSeq,
        List<Outcome> outcomes,
        Instant serverTime,
        Long clockOffsetMs,
        long snapshotVersion,
        List<Instruction> instructions) {

    static final String APPLIED = "APPLIED";
    static final String DUPLICATE = "DUPLICATE";
    static final String QUARANTINED = "QUARANTINED";

    /** What became of one event. {@code reason} is the quarantine reason. */
    record Outcome(long deviceSeq, UUID eventId, String outcome, String reason) {}

    /**
     * Something the device is asked to do (doc 32 section 3.4, "pending instructions"): RESEND_FROM
     * a sequence, FLOOR_NOTICE (update the application).
     */
    record Instruction(String type, Long fromSeq, String detail) {

        static Instruction resendFrom(long seq) {
            return new Instruction("RESEND_FROM", seq, null);
        }

        static Instruction floorNotice(String floor) {
            return new Instruction("FLOOR_NOTICE", null, floor);
        }
    }

    /** The part of the acknowledgement stored on the cursor for a resend of the same batch. */
    record Stored(long lastAppliedSeq, List<Outcome> outcomes) {}
}
