package lk.coopfed.knoweb.kernel.internal.sync;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.security.DeviceScopes;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import org.springframework.stereotype.Component;

/**
 * Device authentication (19A section 2; doc 19 section 2.1; doc 32 sections 3.3 step 1 and 9). A
 * verified token with {@code cls = DEVICE} names its device in {@code dev}; this turns it into
 * the device's scope, or refuses it:
 *
 * <pre>
 *   no such device in M1                     403 sync.device_unknown
 *   SUSPENDED                                403 sync.device_suspended, params.revoke = the signed
 *   RETIRED                                  403 sync.device_retired      revoke instruction
 *   ENROLLED, or ACTIVE without a position   403 sync.device_not_active
 *   ACTIVE at a position                     the OWN scope of its entity at its position's shop
 * </pre>
 *
 * The device's scope has no user: a device acts for itself, and each event it uploads names its
 * operator. The class is DEVICE, which the connection customizer sets as OWN for the device's
 * entity and location (doc 18 section 3.7), so row-level security shows the device its shop's
 * rows and nothing else. The status comes from {@link DeviceDirectory}, M1's table through a
 * cache that M1's device events empty.
 */
@Component
public class DeviceAuth implements DeviceScopes {

    private final DeviceDirectory directory;
    private final TillSigner signer;
    private final Clock clock;

    DeviceAuth(DeviceDirectory directory, TillSigner signer, Clock clock) {
        this.directory = directory;
        this.signer = signer;
        this.clock = clock;
    }

    /** The scope of an authenticated device, or a {@link ProblemException} that refuses it. */
    @Override
    public ScopeContext scopeOf(UUID deviceId, UUID correlationId, Locale locale) {
        DeviceRecord device = authenticate(deviceId);
        Scope scope = new Scope(device.ownerEntityId(), device.locationId());
        return new ScopeContext(
                null,
                device.deviceId(),
                device.ownerEntityId(),
                List.of(scope),
                scope,
                PolicyClass.DEVICE,
                Set.of(),
                null,
                locale,
                correlationId);
    }

    DeviceRecord authenticate(UUID deviceId) {
        if (deviceId == null) {
            throw new ProblemException("token.invalid");
        }
        DeviceRecord device = directory.find(deviceId).orElseThrow(() -> new ProblemException("sync.device_unknown"));
        switch (device.status()) {
            case "SUSPENDED" -> throw new ProblemException("sync.device_suspended", revoke(device));
            case "RETIRED" -> throw new ProblemException("sync.device_retired", revoke(device));
            default -> {
                if (!device.isActive() || device.locationId() == null) {
                    throw new ProblemException("sync.device_not_active");
                }
                return device;
            }
        }
    }

    /**
     * The revoke instruction of doc 32 section 9: the device wipes its caches and locks, and keeps
     * its unacknowledged outbox rows for an administrator's recovery. Signed, because a till
     * acts on it without asking anyone: a forged one would take a shop off the air.
     */
    Map<String, Object> revoke(DeviceRecord device) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String text = canonical("REVOKE", device.deviceId(), device.status(), issuedAt);
        Map<String, Object> instruction = new LinkedHashMap<>();
        instruction.put("type", "REVOKE");
        instruction.put("device_id", device.deviceId().toString());
        instruction.put("status", device.status());
        instruction.put("issued_at", issuedAt.toString());
        instruction.put("key_id", signer.keyId());
        instruction.put("signature", signer.sign(text));
        return Map.of("revoke", instruction);
    }

    /** What is signed: one field per line, in this order, each line ending in a newline. */
    static String canonical(String type, UUID deviceId, String status, Instant issuedAt) {
        return "type=" + type + "\n" + "device_id=" + deviceId + "\n" + "status=" + status + "\n" + "issued_at="
                + issuedAt + "\n";
    }
}
