package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * Something about a device's sync needs a person (doc 19 section 6.4, sync.anomaly; doc 32
 * section 7): an event quarantined, repeated sequence gaps, a clock that drifted, central holding
 * less than the device was told it holds. The audit record beside it carries the severity.
 *
 * @param anomalyId the quarantine row for a quarantined event; a fresh id otherwise
 * @param kind      QUARANTINED, REPEATED_GAP, CLOCK_DRIFT or CURSOR_BEHIND
 * @param deviceSeq the sequence concerned, when there is one
 * @param reason    for QUARANTINED, the reason (SCHEMA, DUPLICATE_ID, HASH ...)
 */
public record SyncAnomaly(UUID anomalyId, UUID deviceId, String kind, Long deviceSeq, String reason)
        implements DomainEvent {

    public static final String TYPE = "sync.anomaly.v1";
}
