package lk.coopfed.knoweb.m1party.internal.device;

import lk.coopfed.knoweb.kernel.api.DeviceHeartbeatReported;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DeviceHeartbeatConsumer {

    private final DeviceRepository devices;

    DeviceHeartbeatConsumer(DeviceRepository devices) {
        this.devices = devices;
    }

    @Transactional
    @EventConsumer(types = DeviceHeartbeatReported.TYPE, consumer = "m1.device.heartbeat")
    public void consume(DeviceHeartbeatReported event, ScopeContext context) {
        devices.findByIdForUpdate(event.deviceId()).ifPresent(device -> {
            device.updateFromHeartbeat(event.appVersion(), event.lastSeenAt());
            devices.save(device);
        });
    }
}
