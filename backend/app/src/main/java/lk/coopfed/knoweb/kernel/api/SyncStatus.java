package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What central knows of a till's outbox (doc 32 sections 2, 6 and 8). The sync gateway (19A K-08)
 * keeps one cursor per device, {@code device_sync_cursor}: the last sequence number it applied
 * and acknowledged; and the device's last heartbeat, {@code device_heartbeat}: what it said it
 * still had to send. A device is drained when everything it has issued has reached central and
 * been acknowledged, so the numbers it took from its lane's series are all accounted for.
 *
 * <p>M1 asks {@link #drained} before a position's counters move to another device (21A section
 * 6.1, AssignDeviceToPosition: "old device outbox drained (kernel SyncStatus.drained(oldDevice))
 * or loss recorded"). A device that cannot be shown drained blocks the transfer until an
 * administrator records the loss, which makes the series gap documented and never hidden (doc
 * 21 flow 6.6). M1 shows {@link DeviceSyncState#lastSeenAt()} in its device list ("join
 * last_seen from the device row (heartbeat updates it through the kernel)"): the heartbeat is
 * recorded in the kernel's own table, never in M1's.
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

    /**
     * The device's state, read under the caller's scope; empty when it never enrolled for sync
     * or the caller cannot see its entity.
     */
    Optional<DeviceSyncState> state(UUID deviceId, ScopeContext ctx);

    /**
     * Whether every event the device has emitted has been applied and acknowledged at central:
     * its last heartbeat reported nothing pending, no batch of it is in flight, and it holds
     * nothing acknowledged beyond what central holds. An answer of false is also the answer when
     * central cannot tell (no cursor, no heartbeat): the safe side of a counter transfer is to
     * ask for the loss to be recorded. Read in the caller's transaction, under its scope.
     */
    boolean drained(UUID deviceId);
}
