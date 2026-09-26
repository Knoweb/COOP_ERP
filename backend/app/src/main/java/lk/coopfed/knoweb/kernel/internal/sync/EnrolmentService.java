package lk.coopfed.knoweb.kernel.internal.sync;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import org.springframework.stereotype.Component;

/**
 * Device enrolment (doc 32 section 8; 19A section 8, EnrolmentService), in two steps.
 *
 * <ol>
 *   <li>An administrator of the device's entity issues a one-time code ({@link #issueCode}); it
 *       is shown once and stored as a hash.
 *   <li>The till, provisioned as a managed kiosk (doc 31), starts for the first time and presents
 *       the code with its hardware serial ({@link #enrol}); it receives its credential (an OAuth2
 *       client of the identity provider, {@code DeviceCredentials}), the first sequence to use, the
 *       series it holds, the snapshot pointer and the key that signs what central sends it.
 * </ol>
 *
 * M1 registers the device and assigns it to a till position (21A section 6); the kernel reads
 * M1's device and writes only its own tables: the code, the cursor, and the event
 * {@code device.sync_enrolled.v1}, which M1 may consume.
 */
@Component
class EnrolmentService {

    /**
     * Capitals and digits without the ambiguous ones (0 and O, 1 and I): 32 characters, 5 bits
     * each, so sixteen characters are 80 bits, beyond guessing within the code's lifetime. Built
     * from the ranges, which reads better and does not look like a credential to a secret scanner.
     */
    private static final char[] CODE_CHARACTERS = codeCharacters();

    static final int CODE_LENGTH = 16;

    /** What the administrator is shown. */
    record IssuedCode(UUID deviceId, String code, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedCode[deviceId=" + deviceId + ", code hidden, expiresAt=" + expiresAt + "]";
        }
    }

    private final DeviceDirectory directory;
    private final EnrolmentStore store;
    private final SyncSettings settings;
    private final java.time.Clock clock;
    private final SecureRandom random = new SecureRandom();

    EnrolmentService(DeviceDirectory directory, EnrolmentStore store, SyncSettings settings, java.time.Clock clock) {
        this.directory = directory;
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    IssuedCode issueCode(ScopeContext ctx, UUID deviceId) {
        DeviceRecord device = directory.findFresh(deviceId).orElse(null);
        String code = newCode();
        java.time.Duration ttl = settings.enrolmentCodeTtl(ctx);
        Instant expiresAt = store.issueCode(ctx, device, code, ttl, EnrolmentStore.newId());
        return new IssuedCode(deviceId, code, expiresAt);
    }

    /** The idempotency key is the slice's: a retry with the same key is answered again. */
    EnrolmentStore.Enrolled enrol(
            UUID deviceId,
            String code,
            String hardwareSerial,
            String appVersion,
            String idempotencyKey,
            UUID correlation) {
        DeviceRecord device =
                directory.findFresh(deviceId).orElseThrow(() -> new ProblemException("sync.enrolment.code_invalid"));
        return store.enrol(scopeOf(device, correlation), device, code, hardwareSerial, appVersion, idempotencyKey);
    }

    /**
     * The device's own scope for the enrolment's writes: its entity, at its shop when it has one
     * (the code check comes first, and an unassigned device is refused after it).
     */
    private static ScopeContext scopeOf(DeviceRecord device, UUID correlation) {
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
                Locale.ENGLISH,
                correlation);
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_CHARACTERS[random.nextInt(CODE_CHARACTERS.length)]);
        }
        return code.toString();
    }

    private static char[] codeCharacters() {
        StringBuilder all = new StringBuilder();
        for (char c = 'A'; c <= 'Z'; c++) {
            if (c != 'I' && c != 'O') {
                all.append(c);
            }
        }
        for (char c = '2'; c <= '9'; c++) {
            all.append(c);
        }
        return all.toString().toCharArray();
    }
}
