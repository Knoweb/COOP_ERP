package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A device took a free position that no device held before (21A section 6,
 * AssignDeviceToPosition). The position's series now name it as their holder; the kernel
 * published series.holder_changed.v1 for each one listed.
 */
public record DeviceAssigned(
        UUID assignedDeviceId, UUID ownerEntityId, UUID locationId, UUID tillPositionId, List<UUID> seriesMoved)
        implements DomainEvent {

    public static final String TYPE = "device.assigned.v1";
}
