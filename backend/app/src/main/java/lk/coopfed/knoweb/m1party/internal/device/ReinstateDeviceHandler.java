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
import lk.coopfed.knoweb.m1party.api.DeviceReinstated;
import lk.coopfed.knoweb.m1party.api.ReinstateDevice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, ReinstateDevice: SUSPENDED to ACTIVE once the device is physically recovered
 * (doc 21 section 4.5; the reason says so); DEVICE_REINSTATED; device.reinstated.v1, on which
 * the sync gateway lifts its revoke. A device whose position was taken by a replacement
 * meanwhile comes back ACTIVE without a position, ready to be assigned again.
 */
@Service
@CommandHandler(permission = "sys.device.suspend", requiresMfa = true)
class ReinstateDeviceHandler implements Handles<ReinstateDevice, UUID> {

    static final String AUDIT_REINSTATED = "DEVICE_REINSTATED";

    private final DeviceRepository devices;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReinstateDeviceHandler(DeviceRepository devices, AuditFacade audit, EventPublisher events) {
        this.devices = devices;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReinstateDevice command, ScopeContext scope) {

        DeviceGuards.requireOwnScope(scope);
        Device device = DeviceGuards.device(devices, command.deviceId());
        // 1. only a SUSPENDED device is reinstated.
        if (!device.isSuspended()) {
            throw new ProblemException(
                    "m1.device.not_suspended", Map.of("deviceId", device.getId(), "status", device.status()));
        }
        // 2. a reason: the physical recovery confirmed.
        String reason = DeviceGuards.reason(command.reasonCode(), command.reasonText());

        Map<String, Object> before = device.auditState();
        device.reinstate();
        devices.saveAndFlush(device);

        audit.record(
                AUDIT_REINSTATED, Subject.of("device", device.getId()), before, device.auditState(), scope, reason);
        events.publish(new DeviceReinstated(
                device.getId(),
                device.ownerEntityId(),
                device.locationId(),
                device.currentTillPositionId(),
                command.reasonCode().strip()));

        return device.getId();
    }
}
