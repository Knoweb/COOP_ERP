package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DeviceCredentials;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Empties the device cache when M1 changes a device (21A section 6: EnrolDevice,
 * AssignDeviceToPosition, SuspendDevice, Reinstate, Retire, "gateway turns suspended into a
 * revoke"), and disables a retired device's credential at the identity provider. A suspended
 * device keeps its credential: it must still reach central to be told, with the signed revoke,
 * that it is suspended (doc 19 section 2.1).
 *
 * <p>M1's device events are being written (M1-06) while this is: every UUID field of the payload
 * whose name ends in "deviceId" (deviceId, previousDeviceId, enrolledDeviceId ...) is emptied,
 * and a payload with none empties the whole cache. The expiry of {@link DeviceDirectory} bounds
 * the staleness where the consumer runtime does not run.
 */
@Component
class DeviceCacheInvalidator {

    static final String CONSUMER = "kernel-device-cache";

    private static final Logger log = LoggerFactory.getLogger(DeviceCacheInvalidator.class);

    private final DeviceDirectory directory;
    private final DeviceCredentials credentials;

    DeviceCacheInvalidator(DeviceDirectory directory, DeviceCredentials credentials) {
        this.directory = directory;
        this.credentials = credentials;
    }

    @EventConsumer(
            types = {
                "device.enrolled.v1",
                "device.assigned.v1",
                "device.position_changed.v1",
                "device.suspended.v1",
                "device.reinstated.v1",
                "device.retired.v1",
                "location.primary_changed.v1"
            },
            consumer = CONSUMER)
    public void onDeviceChanged(JsonNode payload, ScopeContext scope) {
        if (!forgetDevicesNamedIn(payload)) {
            directory.invalidateAll();
        }
    }

    @EventConsumer(types = "device.retired.v1", consumer = CONSUMER + "-credential")
    public void onDeviceRetired(JsonNode payload, ScopeContext scope) {
        UUID device = firstDeviceId(payload);
        if (device == null) {
            log.warn("device.retired.v1 without a device id; no credential disabled");
            return;
        }
        credentials.revoke(device);
    }

    boolean forgetDevicesNamedIn(JsonNode payload) {
        boolean any = false;
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            UUID device = deviceIdIn(field);
            if (device != null) {
                directory.invalidate(device);
                any = true;
            }
        }
        return any;
    }

    private static UUID firstDeviceId(JsonNode payload) {
        JsonNode direct = payload.path("deviceId");
        if (direct.isTextual()) {
            return parse(direct.asText());
        }
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            UUID device = deviceIdIn(fields.next());
            if (device != null) {
                return device;
            }
        }
        return null;
    }

    private static UUID deviceIdIn(Map.Entry<String, JsonNode> field) {
        if (!field.getKey().endsWith("eviceId") || !field.getValue().isTextual()) {
            return null;
        }
        return parse(field.getValue().asText());
    }

    private static UUID parse(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
