package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * What central knows of a till's outbox (doc 32 sections 2 and 8). The sync gateway (19A K-08)
 * keeps one cursor per device, {@code device_sync_cursor}: the last sequence number it applied
 * and acknowledged. A device is drained when everything it has issued has reached central and
 * been acknowledged, so the numbers it took from its lane's series are all accounted for.
 *
 * <p>M1 asks this before a position's counters move to another device (21A section 6.1,
 * AssignDeviceToPosition: "old device outbox drained (kernel SyncStatus.drained(oldDevice)) or
 * loss recorded"). A device that cannot be shown drained blocks the transfer until an
 * administrator records the loss, which makes the series gap documented and never hidden (doc
 * 21 flow 6.6).
 */
public interface SyncStatus {

    /**
     * Whether every event the device has emitted has been applied and acknowledged at central.
     * An answer of false is also the answer when central cannot tell: the safe side of a counter
     * transfer is to ask for the loss to be recorded.
     */
    boolean drained(UUID deviceId);
}
