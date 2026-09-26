package lk.coopfed.knoweb.kernel.internal.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DeviceCredentials;
import lk.coopfed.knoweb.kernel.api.DeviceSyncEnrolled;
import lk.coopfed.knoweb.kernel.api.EnrolmentCodeIssued;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.security.StepUp;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two transactions of enrolment (doc 32 section 8): an administrator issues a one-time code
 * for a device M1 registered; the device spends it once and receives its credential, its cursor,
 * the series it holds and the snapshot pointer. Public {@code @Transactional} methods taking the
 * scope they act in; {@link EnrolmentService} looks the device up first, outside any
 * transaction.
 */
@Component
public class EnrolmentStore {

    static final String AUDIT_CODE_ISSUED = "DEVICE_ENROLMENT_CODE_ISSUED";
    static final String AUDIT_ENROLLED = "DEVICE_SYNC_ENROLLED";

    /** The permission of the catalogue that lets an administrator enrol and suspend devices (M1). */
    static final String PERMISSION = "sys.device.manage";

    /** What the device receives. */
    record Enrolled(
            DeviceRecord device,
            DeviceCredentials.Credential credential,
            long nextDeviceSeq,
            List<Series> series,
            long snapshotVersion) {}

    /** A numbering series the device holds (doc 32 section 8, series assignment). */
    record Series(UUID seriesId, String docTypeCode, String scope, String prefix, long nextNumber) {}

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PermissionResolver permissions;
    private final DeviceCredentials credentials;
    private final Clock clock;
    private final StepUp stepUp;
    private final boolean enforcePermissions;

    EnrolmentStore(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            PermissionResolver permissions,
            DeviceCredentials credentials,
            Clock clock,
            StepUp stepUp,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.permissions = permissions;
        this.credentials = credentials;
        this.clock = clock;
        this.stepUp = stepUp;
        this.enforcePermissions = enforcePermissions;
    }

    /**
     * Issues a code: the caller holds sys.device.manage with a fresh second factor (the catalogue
     * asks for one), the device is M1's in the caller's entity and ENROLLED or ACTIVE. Any code of
     * the device not yet used is withdrawn.
     */
    @Transactional
    public Instant issueCode(ScopeContext ctx, DeviceRecord device, String code, Duration ttl, UUID codeId) {
        checkPermission(ctx);
        if (device == null
                || !device.ownerEntityId().equals(ctx.entityId())
                || !("ENROLLED".equals(device.status()) || "ACTIVE".equals(device.status()))) {
            throw new ProblemException("sync.enrolment.device_not_enrollable");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        jdbc.update(
                """
                update kernel.device_enrolment_code set withdrawn_at = ?
                 where device_id = ? and used_at is null and withdrawn_at is null
                """,
                Timestamp.from(now),
                device.deviceId());
        jdbc.update(
                """
                insert into kernel.device_enrolment_code (enrolment_code_id, device_id, owner_entity_id, code_hash,
                                                          issued_by_user_id, issued_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                codeId,
                device.deviceId(),
                device.ownerEntityId(),
                hash(code),
                ctx.userId(),
                Timestamp.from(now),
                Timestamp.from(expiresAt));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enrolmentCodeId", codeId);
        after.put("expiresAt", expiresAt.toString());
        audit.record(AUDIT_CODE_ISSUED, Subject.of("device", device.deviceId()), null, after, ctx);
        events.publish(new EnrolmentCodeIssued(codeId, device.deviceId(), expiresAt));
        return expiresAt;
    }

    /**
     * Spends the code and enrols the device, in the device's own scope. A wrong, spent, withdrawn
     * or expired code and a hardware serial other than M1's are one answer, so a guess learns
     * nothing; the code is spent only when everything else succeeded, the credential included.
     */
    @Transactional
    public Enrolled enrol(
            ScopeContext deviceScope, DeviceRecord device, String code, String hardwareSerial, String appVersion) {
        Instant now = clock.instant();
        List<Map<String, Object>> open = jdbc.queryForList(
                """
                select enrolment_code_id, code_hash, expires_at from kernel.device_enrolment_code
                 where device_id = ? and used_at is null and withdrawn_at is null
                 order by issued_at desc
                 limit 1
                """,
                device.deviceId());
        boolean valid = !open.isEmpty()
                && MessageDigest.isEqual(
                        ((String) open.getFirst().get("code_hash")).getBytes(StandardCharsets.US_ASCII),
                        hash(code).getBytes(StandardCharsets.US_ASCII))
                && ((Timestamp) open.getFirst().get("expires_at")).toInstant().isAfter(now)
                && device.hardwareSerial().equals(hardwareSerial.strip());
        if (!valid) {
            throw new ProblemException("sync.enrolment.code_invalid");
        }
        if (!device.isActive() || device.locationId() == null) {
            throw new ProblemException("sync.enrolment.device_not_assigned");
        }

        DeviceCredentials.Credential credential = credentials.issue(device.deviceId(), device.ownerEntityId());

        jdbc.update(
                "update kernel.device_enrolment_code set used_at = ? where enrolment_code_id = ?",
                Timestamp.from(now),
                open.getFirst().get("enrolment_code_id"));
        List<Long> cursor = jdbc.queryForList(
                "select last_applied_seq from kernel.device_sync_cursor where device_id = ? for update",
                Long.class,
                device.deviceId());
        long lastApplied;
        if (cursor.isEmpty()) {
            jdbc.update(
                    """
                    insert into kernel.device_sync_cursor (device_id, owner_entity_id, last_applied_seq, app_version,
                                                           credential_client_id, enrolled_at, last_enrolled_at)
                    values (?, ?, 0, ?, ?, ?, ?)
                    """,
                    device.deviceId(),
                    device.ownerEntityId(),
                    appVersion,
                    credential.clientId(),
                    Timestamp.from(now),
                    Timestamp.from(now));
            lastApplied = 0;
        } else {
            // Enrolling again (a re-installed application) keeps the sequence: "never reset except
            // by the audited recovery procedure" (doc 32 section 2).
            jdbc.update(
                    """
                    update kernel.device_sync_cursor
                       set app_version = ?, credential_client_id = ?, last_enrolled_at = ?
                     where device_id = ?
                    """,
                    appVersion,
                    credential.clientId(),
                    Timestamp.from(now),
                    device.deviceId());
            lastApplied = cursor.getFirst();
        }

        List<Series> series = jdbc.query(
                """
                select series_id, doc_type_code, series_scope, prefix, next_number
                  from kernel.numbering_series
                 where status = 'ACTIVE'
                   and owner_entity_id = ?
                   and location_id = ?
                   and ((series_scope = 'TILL_POSITION' and till_position_id = ?)
                        or (series_scope = 'LOCATION' and ?))
                 order by series_scope, doc_type_code
                """,
                (rs, n) -> new Series(
                        rs.getObject("series_id", UUID.class),
                        rs.getString("doc_type_code"),
                        rs.getString("series_scope"),
                        rs.getString("prefix"),
                        rs.getLong("next_number")),
                device.ownerEntityId(),
                device.locationId(),
                device.tillPositionId(),
                device.primaryTill());
        List<Long> version = jdbc.queryForList(
                "select current_version from kernel.location_snapshot_version where location_id = ?",
                Long.class,
                device.locationId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("tillPositionId", device.tillPositionId());
        after.put("locationId", device.locationId());
        after.put("clientId", credential.clientId());
        after.put("nextDeviceSeq", lastApplied + 1);
        audit.record(AUDIT_ENROLLED, Subject.of("device", device.deviceId()), null, after, deviceScope);
        events.publish(new DeviceSyncEnrolled(
                device.deviceId(),
                device.ownerEntityId(),
                device.locationId(),
                device.tillPositionId(),
                lastApplied + 1,
                now));
        return new Enrolled(device, credential, lastApplied + 1, series, version.isEmpty() ? 0 : version.getFirst());
    }

    /**
     * The handler rule of the modules (K-03b), for a kernel operation: the permission in the
     * caller's scope, then a fresh second factor where the catalogue asks for one; refused only
     * when enforcement is on (coop-erp.security.enforce-permissions), as for every command.
     */
    private void checkPermission(ScopeContext ctx) {
        if (ctx.userId() == null || ctx.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("permission.denied", Map.of("permission", PERMISSION));
        }
        if (!enforcePermissions) {
            return;
        }
        if (!permissions.allows(ctx, PERMISSION)) {
            throw new ProblemException("permission.denied", Map.of("permission", PERMISSION));
        }
        if (permissions.requiresMfa(PERMISSION) && !stepUp.isFresh(ctx)) {
            throw new ProblemException("mfa.required", Map.of("permission", PERMISSION));
        }
    }

    static String hash(String code) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(normalise(code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** What the person typed, as the code was issued: capitals, no spaces or dashes. */
    static String normalise(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }

    static UUID newId() {
        return Ids.next();
    }
}
