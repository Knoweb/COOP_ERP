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
import lk.coopfed.knoweb.m1party.api.DeviceRetired;
import lk.coopfed.knoweb.m1party.api.DeviceRevoked;
import lk.coopfed.knoweb.m1party.api.RetireDevice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, RetireDevice: from any state but RETIRED; retire requires unassigned (a device
 * holding a position is relieved by assigning a replacement there first, flow 6.6); a reason,
 * the wipe confirmed (doc 21 section 4.5); status RETIRED; DEVICE_RETIRED; device.retired.v1 and
 * device.revoked.v1, so the sync gateway refuses the device for good. The row stays: devices
 * are never deleted, and their history is the audit trail.
 */
@Service
@CommandHandler(permission = "sys.device.suspend", requiresMfa = true)
class RetireDeviceHandler implements Handles<RetireDevice, UUID> {

    static final String AUDIT_RETIRED = "DEVICE_RETIRED";

    private final DeviceRepository devices;
    private final AuditFacade audit;
    private final EventPublisher events;

    RetireDeviceHandler(DeviceRepository devices, AuditFacade audit, EventPublisher events) {
        this.devices = devices;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RetireDevice command, ScopeContext scope) {

        DeviceGuards.requireOwnScope(scope);
        Device device = DeviceGuards.device(devices, command.deviceId());
        // 1. not retired already.
        if (device.isRetired()) {
            throw new ProblemException("m1.device.already_retired", Map.of("deviceId", device.getId()));
        }
        // 2. unassigned.
        if (device.currentTillPositionId() != null) {
            throw new ProblemException(
                    "m1.device.still_assigned",
                    Map.of("deviceId", device.getId(), "tillPositionId", device.currentTillPositionId()));
        }
        // 3. a reason: the wipe confirmed.
        String reason = DeviceGuards.reason(command.reasonCode(), command.reasonText());

        Map<String, Object> before = device.auditState();
        device.retire();
        devices.saveAndFlush(device);

        String reasonCode = command.reasonCode().strip();
        audit.record(AUDIT_RETIRED, Subject.of("device", device.getId()), before, device.auditState(), scope, reason);
        events.publish(new DeviceRetired(device.getId(), device.ownerEntityId(), device.locationId(), reasonCode));
        events.publish(new DeviceRevoked(
                device.getId(),
                device.ownerEntityId(),
                device.locationId(),
                device.hardwareSerial(),
                DeviceRevoked.CAUSE_RETIRED,
                reasonCode));

        return device.getId();
    }
}
