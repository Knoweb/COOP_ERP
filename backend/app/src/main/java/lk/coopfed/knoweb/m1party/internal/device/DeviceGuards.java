package lk.coopfed.knoweb.m1party.internal.device;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guards every device handler shares, each with its message id. */
final class DeviceGuards {

    private DeviceGuards() {}

    /**
     * A device is managed by its own entity, in an OWN scope: entity-wide, or at the shop the
     * device belongs to. The Federation view and an external grant read devices and write none
     * (doc 18 section 3.7); row-level security says the same, this says it in words.
     */
    static void requireOwnScope(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m1.device.scope_required");
        }
    }

    /** The device, if the caller may see it: another entity's or another shop's is "not found". */
    static Device device(DeviceRepository devices, UUID deviceId) {
        if (deviceId == null) {
            throw new ProblemException("m1.device.not_found", Map.of("deviceId", "null"));
        }
        return devices.findById(deviceId)
                .orElseThrow(() -> new ProblemException("m1.device.not_found", Map.of("deviceId", deviceId)));
    }

    /**
     * The device, locked until the transaction ends: for AssignDeviceToPosition, where two
     * administrators assigning the same device at once must run one after the other.
     */
    static Device lockedDevice(DeviceRepository devices, UUID deviceId) {
        if (deviceId == null) {
            throw new ProblemException("m1.device.not_found", Map.of("deviceId", "null"));
        }
        return devices.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ProblemException("m1.device.not_found", Map.of("deviceId", deviceId)));
    }

    /** A reason code, and the text of the audit record's reason: "CODE" or "CODE: words". */
    static String reason(String reasonCode, String reasonText) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ProblemException("m1.device.reason_required");
        }
        String code = reasonCode.strip();
        return reasonText == null || reasonText.isBlank() ? code : code + ": " + reasonText.strip();
    }
}
