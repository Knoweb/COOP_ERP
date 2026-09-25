package lk.coopfed.knoweb.m1party.internal.device;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.DeviceRevoked;
import lk.coopfed.knoweb.m1party.api.DeviceSuspended;
import lk.coopfed.knoweb.m1party.api.SuspendDevice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, SuspendDevice: transition valid (ACTIVE to SUSPENDED, doc 21 section 4.5);
 * reason (lost, stolen, faulty); status SUSPENDED; DEVICE_SUSPENDED; device.suspended.v1 and the
 * revoke the sync gateway relays to the till (device.revoked.v1, doc 32 section 9).
 *
 * <p>The device keeps its position ("keeps position for return", 21A): a device found again is
 * reinstated into its own lane without moving any counter, and a replacement assigned to the
 * position relieves it (AssignDeviceToPosition). The position's counters stay with the
 * suspended device until then, which is what the drained check of the replacement needs.
 */
@Service
@CommandHandler(permission = "sys.device.suspend", requiresMfa = true)
class SuspendDeviceHandler implements Handles<SuspendDevice, UUID> {

    static final String AUDIT_SUSPENDED = "DEVICE_SUSPENDED";

    private final DeviceRepository devices;
    private final AuditFacade audit;
    private final EventPublisher events;

    SuspendDeviceHandler(DeviceRepository devices, AuditFacade audit, EventPublisher events) {
        this.devices = devices;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SuspendDevice command, ScopeContext scope) {

        DeviceGuards.requireOwnScope(scope);
        Device device = DeviceGuards.device(devices, command.deviceId());
        // 1. only an ACTIVE device is suspended.
        if (!device.isActive()) {
            throw new ProblemException(
                    "m1.device.not_active", Map.of("deviceId", device.getId(), "status", device.status()));
        }
        // 2. a reason.
        String reason = DeviceGuards.reason(command.reasonCode(), command.reasonText());

        Map<String, Object> before = device.auditState();
        device.suspend();
        devices.saveAndFlush(device);

        String reasonCode = command.reasonCode().strip();
        audit.record(AUDIT_SUSPENDED, Subject.of("device", device.getId()), before, device.auditState(), scope, reason);
        events.publish(new DeviceSuspended(
                device.getId(),
                device.ownerEntityId(),
                device.locationId(),
                device.currentTillPositionId(),
                reasonCode));
        events.publish(new DeviceRevoked(
                device.getId(),
                device.ownerEntityId(),
                device.locationId(),
                device.hardwareSerial(),
                DeviceRevoked.CAUSE_SUSPENDED,
                reasonCode));

        return device.getId();
    }
}
