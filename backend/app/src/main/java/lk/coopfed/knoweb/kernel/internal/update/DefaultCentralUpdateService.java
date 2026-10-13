package lk.coopfed.knoweb.kernel.internal.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CentralUpdateService;
import lk.coopfed.knoweb.kernel.api.DeviceRingAssigned;
import lk.coopfed.knoweb.kernel.api.DeviceUpdateManifest;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.PublishReleaseCommand;
import lk.coopfed.knoweb.kernel.api.ReleasePaused;
import lk.coopfed.knoweb.kernel.api.ReleasePublished;
import lk.coopfed.knoweb.kernel.api.ReleaseRecord;
import lk.coopfed.knoweb.kernel.api.ReleaseResumed;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.AppVersions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultCentralUpdateService implements CentralUpdateService {

    public static final String AUDIT_RELEASE_PUBLISHED = "RELEASE_PUBLISHED";
    public static final String AUDIT_RELEASE_PAUSED = "RELEASE_PAUSED";
    public static final String AUDIT_RELEASE_RESUMED = "RELEASE_RESUMED";
    public static final String AUDIT_DEVICE_RING_ASSIGNED = "DEVICE_RING_ASSIGNED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PackageStore packageStore;
    private final ReleaseSigner releaseSigner;
    private final Clock clock;
    private final SystemScope systemScope;
    private final ObjectMapper json;

    public DefaultCentralUpdateService(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            PackageStore packageStore,
            ReleaseSigner releaseSigner,
            Clock clock,
            SystemScope systemScope,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.packageStore = packageStore;
        this.releaseSigner = releaseSigner;
        this.clock = clock;
        this.systemScope = systemScope;
        this.json = json;
    }

    @Override
    @Transactional
    public ReleaseRecord publishRelease(PublishReleaseCommand cmd, ScopeContext scope) {
        checkFederationScope(scope);

        if (cmd == null) {
            throw new ProblemException("request.field.required");
        }
        if (cmd.version() == null || !cmd.version().matches("^[0-9]{1,5}(\\.[0-9]{1,5}){0,3}$")) {
            throw new ProblemException("release.version_invalid");
        }
        if (cmd.platform() == null || cmd.platform().isBlank()) {
            throw new ProblemException("release.platform_invalid");
        }
        if (cmd.packagePath() == null || cmd.packagePath().isBlank()) {
            throw new ProblemException("request.field.required");
        }
        if (cmd.packageHash() == null || cmd.packageHash().length() != 64) {
            throw new ProblemException("attachment.hash_invalid");
        }
        if (cmd.packageSizeBytes() <= 0) {
            throw new ProblemException("request.field.invalid");
        }
        if (cmd.minSupportedVersion() == null
                || !cmd.minSupportedVersion().matches("^[0-9]{1,5}(\\.[0-9]{1,5}){0,3}$")) {
            throw new ProblemException("release.version_invalid");
        }
        if (cmd.targetRing() < 0 || cmd.targetRing() > 3) {
            throw new ProblemException("device.ring_invalid");
        }

        List<UUID> existing = jdbc.queryForList(
                "select release_id from kernel.app_release where version = ? and platform = ?",
                UUID.class,
                cmd.version(),
                cmd.platform());
        if (!existing.isEmpty()) {
            throw new ProblemException("release.already_exists");
        }

        Instant now = clock.instant();
        UUID releaseId = Ids.next();
        UUID ownerEntityId = systemScope
                .own()
                .map(ScopeContext::entityId)
                .orElseGet(() -> scope != null ? scope.entityId() : Ids.next());
        String publishedBy =
                scope != null && scope.userId() != null ? scope.userId().toString() : "system";

        jdbc.update(
                """
                insert into kernel.app_release (
                    release_id, owner_entity_id, version, platform, package_path, package_hash,
                    package_size_bytes, min_supported_version, target_ring, status, published_at,
                    published_by, notes, metadata
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, ?, cast(? as jsonb))
                """,
                releaseId,
                ownerEntityId,
                cmd.version(),
                cmd.platform(),
                cmd.packagePath(),
                cmd.packageHash(),
                cmd.packageSizeBytes(),
                cmd.minSupportedVersion(),
                cmd.targetRing(),
                Timestamp.from(now),
                publishedBy,
                cmd.notes(),
                toJson(cmd.metadata()));

        audit.record(
                AUDIT_RELEASE_PUBLISHED,
                Subject.of("release", releaseId),
                null,
                Map.of("version", cmd.version(), "platform", cmd.platform(), "targetRing", cmd.targetRing()),
                scope,
                "Till application release published");

        events.publish(
                new ReleasePublished(Ids.next(), releaseId, cmd.version(), cmd.platform(), cmd.targetRing(), now));

        return new ReleaseRecord(
                releaseId,
                ownerEntityId,
                cmd.version(),
                cmd.platform(),
                cmd.packagePath(),
                cmd.packageHash(),
                cmd.packageSizeBytes(),
                cmd.minSupportedVersion(),
                cmd.targetRing(),
                "PUBLISHED",
                now,
                publishedBy,
                null,
                null,
                cmd.notes());
    }

    @Override
    @Transactional
    public ReleaseRecord pauseRelease(String version, String platform, String reason, ScopeContext scope) {
        checkFederationScope(scope);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select release_id, status from kernel.app_release where version = ? and platform = ?",
                version,
                platform);
        if (rows.isEmpty()) {
            throw new ProblemException("release.not_found");
        }
        UUID releaseId = (UUID) rows.getFirst().get("release_id");
        String status = (String) rows.getFirst().get("status");
        if ("PAUSED".equals(status)) {
            throw new ProblemException("release.already_paused");
        }
        if ("REVOKED".equals(status)) {
            throw new ProblemException("release.revoked");
        }

        Instant now = clock.instant();
        String pausedBy =
                scope != null && scope.userId() != null ? scope.userId().toString() : "system";

        jdbc.update(
                """
                update kernel.app_release
                   set status = 'PAUSED', paused_at = ?, paused_by = ?, notes = concat_ws(E'\\n', notes, ?)
                 where release_id = ?
                """,
                Timestamp.from(now),
                pausedBy,
                reason,
                releaseId);

        audit.record(
                AUDIT_RELEASE_PAUSED,
                Subject.of("release", releaseId),
                Map.of("status", "PUBLISHED"),
                Map.of("status", "PAUSED", "reason", reason != null ? reason : ""),
                scope,
                "Till application release paused");

        events.publish(new ReleasePaused(Ids.next(), releaseId, version, platform, reason, now));

        return doGetRelease(version, platform).orElseThrow();
    }

    @Override
    @Transactional
    public ReleaseRecord resumeRelease(String version, String platform, ScopeContext scope) {
        checkFederationScope(scope);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select release_id, status from kernel.app_release where version = ? and platform = ?",
                version,
                platform);
        if (rows.isEmpty()) {
            throw new ProblemException("release.not_found");
        }
        UUID releaseId = (UUID) rows.getFirst().get("release_id");
        String status = (String) rows.getFirst().get("status");
        if (!"PAUSED".equals(status)) {
            throw new ProblemException("release.not_paused");
        }

        Instant now = clock.instant();

        jdbc.update(
                """
                update kernel.app_release
                   set status = 'PUBLISHED', paused_at = null, paused_by = null
                 where release_id = ?
                """,
                releaseId);

        audit.record(
                AUDIT_RELEASE_RESUMED,
                Subject.of("release", releaseId),
                Map.of("status", "PAUSED"),
                Map.of("status", "PUBLISHED"),
                scope,
                "Till application release resumed");

        events.publish(new ReleaseResumed(Ids.next(), releaseId, version, platform, now));

        return doGetRelease(version, platform).orElseThrow();
    }

    @Override
    @Transactional
    public void assignDeviceRing(UUID deviceId, UUID ownerEntityId, int ring, ScopeContext scope) {
        if (deviceId == null) {
            throw new ProblemException("request.field.required");
        }
        if (ring < 0 || ring > 3) {
            throw new ProblemException("device.ring_invalid");
        }

        UUID owner = ownerEntityId != null
                ? ownerEntityId
                : (scope != null
                        ? scope.entityId()
                        : systemScope.own().map(ScopeContext::entityId).orElseGet(Ids::next));

        Instant now = clock.instant();
        String assignedBy =
                scope != null && scope.userId() != null ? scope.userId().toString() : "system";

        jdbc.update(
                """
                insert into kernel.device_rollout_ring (device_id, owner_entity_id, ring, assigned_at, assigned_by)
                values (?, ?, ?, ?, ?)
                on conflict (device_id) do update set
                    ring = excluded.ring, assigned_at = excluded.assigned_at, assigned_by = excluded.assigned_by
                """,
                deviceId,
                owner,
                ring,
                Timestamp.from(now),
                assignedBy);

        audit.record(
                AUDIT_DEVICE_RING_ASSIGNED,
                Subject.of("device", deviceId),
                null,
                Map.of("ring", ring),
                scope,
                "Device rollout ring assigned");

        events.publish(new DeviceRingAssigned(Ids.next(), deviceId, ring, now));
    }

    @Override
    public Optional<ReleaseRecord> resolveTargetRelease(UUID deviceId, String currentVersion, String platform) {
        return systemScope.inOwnTransaction(
                SystemScope.federationView(), () -> doResolveTargetRelease(deviceId, currentVersion, platform));
    }

    private Optional<ReleaseRecord> doResolveTargetRelease(UUID deviceId, String currentVersion, String platform) {
        int deviceRing = 3;
        if (deviceId != null) {
            List<Integer> rings = jdbc.queryForList(
                    "select ring from kernel.device_rollout_ring where device_id = ?", Integer.class, deviceId);
            if (!rings.isEmpty() && rings.getFirst() != null) {
                deviceRing = rings.getFirst();
            }
        }

        List<ReleaseRecord> candidates = jdbc.query(
                """
                select release_id, owner_entity_id, version, platform, package_path, package_hash,
                       package_size_bytes, min_supported_version, target_ring, status, published_at,
                       published_by, paused_at, paused_by, notes
                  from kernel.app_release
                 where (platform = ? or platform = 'ALL')
                   and status = 'PUBLISHED'
                   and target_ring >= ?
                """,
                (rs, i) -> mapRelease(rs),
                platform,
                deviceRing);

        Optional<ReleaseRecord> highest =
                candidates.stream().max((r1, r2) -> AppVersions.compare(r1.version(), r2.version()));

        if (highest.isEmpty()) {
            return Optional.empty();
        }

        ReleaseRecord top = highest.get();
        if (currentVersion != null
                && !currentVersion.isBlank()
                && AppVersions.compare(top.version(), currentVersion) <= 0) {
            return Optional.empty();
        }

        return Optional.of(top);
    }

    @Override
    public DeviceUpdateManifest getDeviceUpdateManifest(UUID deviceId, String currentVersion, String platform) {
        Optional<ReleaseRecord> targetOpt = resolveTargetRelease(deviceId, currentVersion, platform);
        Instant now = clock.instant();

        if (targetOpt.isPresent()) {
            ReleaseRecord target = targetOpt.get();
            URI downloadUri = packageStore.presignedDownloadUri(target.packagePath(), Duration.ofHours(24));
            String urlStr = downloadUri != null ? downloadUri.toString() : target.packagePath();

            String canonical = canonicalManifestText(
                    deviceId,
                    target.version(),
                    target.platform(),
                    target.packageHash(),
                    target.packageSizeBytes(),
                    target.minSupportedVersion(),
                    target.targetRing(),
                    now);
            String signature = releaseSigner.sign(canonical);

            return new DeviceUpdateManifest(
                    deviceId,
                    target.version(),
                    target.platform(),
                    urlStr,
                    target.packageHash(),
                    target.packageSizeBytes(),
                    target.minSupportedVersion(),
                    target.targetRing(),
                    now,
                    signature,
                    releaseSigner.keyId(),
                    true);
        }

        String fallbackVersion = currentVersion != null && !currentVersion.isBlank() ? currentVersion : "0.0.0";
        String canonical = canonicalManifestText(deviceId, fallbackVersion, platform, "", 0L, "0.0.0", 3, now);
        String signature = releaseSigner.sign(canonical);

        return new DeviceUpdateManifest(
                deviceId,
                fallbackVersion,
                platform,
                null,
                null,
                0L,
                "0.0.0",
                3,
                now,
                signature,
                releaseSigner.keyId(),
                false);
    }

    @Override
    public boolean verifyManifest(DeviceUpdateManifest manifest) {
        if (manifest == null || manifest.signature() == null) {
            return false;
        }
        return releaseSigner.verify(manifest.canonicalText(), manifest.signature());
    }

    @Override
    public Optional<ReleaseRecord> getRelease(String version, String platform) {
        return systemScope.inOwnTransaction(SystemScope.federationView(), () -> doGetRelease(version, platform));
    }

    private Optional<ReleaseRecord> doGetRelease(String version, String platform) {
        List<ReleaseRecord> list = jdbc.query(
                """
                select release_id, owner_entity_id, version, platform, package_path, package_hash,
                       package_size_bytes, min_supported_version, target_ring, status, published_at,
                       published_by, paused_at, paused_by, notes
                  from kernel.app_release
                 where version = ? and platform = ?
                """,
                (rs, i) -> mapRelease(rs),
                version,
                platform);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    @Override
    public List<ReleaseRecord> listReleases(String platform) {
        return systemScope.inOwnTransaction(SystemScope.federationView(), () -> doListReleases(platform));
    }

    private List<ReleaseRecord> doListReleases(String platform) {
        return jdbc.query(
                """
                select release_id, owner_entity_id, version, platform, package_path, package_hash,
                       package_size_bytes, min_supported_version, target_ring, status, published_at,
                       published_by, paused_at, paused_by, notes
                  from kernel.app_release
                 where (platform = ? or ? = 'ALL')
                 order by published_at desc
                """,
                (rs, i) -> mapRelease(rs),
                platform,
                platform);
    }

    public static String canonicalManifestText(
            UUID deviceId,
            String targetVersion,
            String platform,
            String packageHash,
            long packageSizeBytes,
            String minSupportedVersion,
            int targetRing,
            Instant issuedAt) {
        return String.format(
                "device:%s\nversion:%s\nplatform:%s\nhash:%s\nsize:%d\nfloor:%s\nring:%d\nissued:%s",
                deviceId,
                targetVersion,
                platform,
                packageHash != null ? packageHash : "",
                packageSizeBytes,
                minSupportedVersion != null ? minSupportedVersion : "0.0.0",
                targetRing,
                issuedAt);
    }

    private void checkFederationScope(ScopeContext scope) {
        UUID systemEntity = systemScope.own().map(ScopeContext::entityId).orElse(null);
        if (systemEntity != null && scope != null && !systemEntity.equals(scope.entityId())) {
            throw new ProblemException("release.federation_only");
        }
    }

    private ReleaseRecord mapRelease(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        Timestamp pausedAt = rs.getTimestamp("paused_at");
        return new ReleaseRecord(
                rs.getObject("release_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("version"),
                rs.getString("platform"),
                rs.getString("package_path"),
                rs.getString("package_hash"),
                rs.getLong("package_size_bytes"),
                rs.getString("min_supported_version"),
                rs.getInt("target_ring"),
                rs.getString("status"),
                publishedAt != null ? publishedAt.toInstant() : null,
                rs.getString("published_by"),
                pausedAt != null ? pausedAt.toInstant() : null,
                rs.getString("paused_by"),
                rs.getString("notes"));
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new ProblemException("request.malformed");
        }
    }
}
