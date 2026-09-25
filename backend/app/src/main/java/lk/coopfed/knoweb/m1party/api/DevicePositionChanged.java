package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A position changed hands, or a device moved to another position (21A section 6,
 * AssignDeviceToPosition on re-assign; doc 32 section 8, counter transfer). The series of the
 * position (and of the shop, when the position is its primary till) now name the assigned
 * device as their holder.
 *
 * @param previousDeviceId       the suspended device that held the position, or null
 * @param previousTillPositionId the position the assigned device held before, or null
 * @param outboxLossRecorded     true when the device that gave the position up could not be
 *                               shown drained and the administrator recorded the loss: the sync
 *                               gateway records the gap (doc 32 section 8, sequence reset)
 */
public record DevicePositionChanged(
        UUID assignedDeviceId,
        UUID ownerEntityId,
        UUID locationId,
        UUID tillPositionId,
        UUID previousDeviceId,
        UUID previousTillPositionId,
        boolean outboxLossRecorded,
        List<UUID> seriesMoved)
        implements DomainEvent {

    public static final String TYPE = "device.position_changed.v1";
}
