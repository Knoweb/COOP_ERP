package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What central knows of a device's sync (doc 32 sections 2 and 6), for the modules that manage
 * devices. M1 asks {@link #drained} before it moves a till position to a new device (21A section
 * 6, AssignDeviceToPosition: "old device outbox drained (kernel SyncStatus.drained(oldDevice))"),
 * and shows {@link DeviceSyncState#lastSeenAt()} in its device list ("join last_seen from the
 * device row (heartbeat updates it through the kernel)"): the heartbeat is recorded in the
 * kernel's own table, never in M1's.
 *
 * <p>Both read under the caller's scope: a caller who cannot see the device's entity learns
 * nothing.
 */
public interface SyncStatus {

    /**
     * The device's last report and cursor.
     *
     * @param lastSeenAt        the last heartbeat; null when none arrived yet
     * @param appVersion        the application version it last reported
     * @param lastAppliedSeq    the highest sequence central applied from it
     * @param pendingEventCount the events it last said it still had to send; null when unknown
     * @param snapshotVersion   the snapshot version it last said it trades on
     */
    record DeviceSyncState(
            UUID deviceId,
            Instant lastSeenAt,
            String appVersion,
            long lastAppliedSeq,
            Integer pendingEventCount,
            Long snapshotVersion) {}

    /** The device's state, or empty when it never enrolled for sync or the caller cannot see it. */
    Optional<DeviceSyncState> state(UUID deviceId, ScopeContext ctx);

    /**
     * Whether everything the device wrote is at central: its last heartbeat reported nothing
     * pending, and nothing acknowledged beyond what central holds. False when unknown.
     */
    boolean drained(UUID deviceId, ScopeContext ctx);
}
