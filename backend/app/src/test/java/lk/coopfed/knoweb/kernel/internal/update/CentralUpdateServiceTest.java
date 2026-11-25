package lk.coopfed.knoweb.kernel.internal.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DeviceRingAssigned;
import lk.coopfed.knoweb.kernel.api.DeviceUpdateManifest;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.PublishReleaseCommand;
import lk.coopfed.knoweb.kernel.api.ReleasePaused;
import lk.coopfed.knoweb.kernel.api.ReleasePublished;
import lk.coopfed.knoweb.kernel.api.ReleaseRecord;
import lk.coopfed.knoweb.kernel.api.ReleaseResumed;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CentralUpdateServiceTest {

    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID systemEntity = UUID.fromString("0190e000-0000-7000-8000-00000000f0f0");
    private final SystemScope systemScope = new SystemScope(systemEntity.toString());
    private final ScopeContext fedScope = SystemScope.own(systemEntity, null);

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private PackageStore packageStore;
    private ReleaseSigner releaseSigner;
    private DefaultCentralUpdateService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        releaseSigner = new ReleaseSigner("", "", false);
        packageStore = new ObjectStoragePackageStore(new MemoryObjectStore());
        service = new DefaultCentralUpdateService(
                jdbc, audit, events, packageStore, releaseSigner, clock, systemScope, new ObjectMapper());
    }

    @Test
    void releaseSignerSignsAndVerifiesManifest() {
        String data = "device:0190a800\nversion:1.2.0\nhash:abcdef";
        String signature = releaseSigner.sign(data);

        assertThat(signature).isNotNull().isNotBlank();
        assertThat(releaseSigner.keyId()).hasSize(16);
        assertThat(releaseSigner.verify(data, signature)).isTrue();
        assertThat(releaseSigner.verify("tampered data", signature)).isFalse();
        assertThat(releaseSigner.verify(data, "invalid base64")).isFalse();
    }

    @Test
    void packageStoreStoresAndRetrievesPackages() {
        byte[] apk = "raw package data".getBytes(StandardCharsets.UTF_8);
        PackageStore.StoredPackage stored = packageStore.store("ANDROID", "1.4.0", apk);

        assertThat(stored.packagePath()).isEqualTo("releases/android/1.4.0/package.bin");
        assertThat(stored.sizeBytes()).isEqualTo(apk.length);
        assertThat(stored.sha256Hex()).hasSize(64);

        byte[] retrieved = packageStore.retrieve(stored.packagePath());
        assertThat(retrieved).isEqualTo(apk);

        URI uri = packageStore.presignedDownloadUri(stored.packagePath(), Duration.ofHours(1));
        assertThat(uri).isNotNull();
    }

    @Test
    void publishReleaseValidatesGuardsAndPersists() {
        PublishReleaseCommand cmd = new PublishReleaseCommand(
                "1.2.0",
                "ANDROID",
                "releases/android/1.2.0/package.bin",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                1024L,
                "1.0.0",
                3,
                "Version 1.2.0 release",
                Map.of("ring", "fleet"));

        when(jdbc.queryForList(anyString(), eq(UUID.class), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        ReleaseRecord record = service.publishRelease(cmd, fedScope);

        assertThat(record.version()).isEqualTo("1.2.0");
        assertThat(record.platform()).isEqualTo("ANDROID");
        assertThat(record.status()).isEqualTo("PUBLISHED");

        verify(audit)
                .record(
                        eq(DefaultCentralUpdateService.AUDIT_RELEASE_PUBLISHED),
                        any(),
                        any(),
                        any(),
                        eq(fedScope),
                        anyString());

        ArgumentCaptor<ReleasePublished> eventCaptor = ArgumentCaptor.forClass(ReleasePublished.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().version()).isEqualTo("1.2.0");
        assertThat(eventCaptor.getValue().targetRing()).isEqualTo(3);
    }

    @Test
    void publishReleaseRejectsInvalidInputs() {
        // Invalid semver
        PublishReleaseCommand badVersion = new PublishReleaseCommand(
                "1.2.alpha",
                "ANDROID",
                "path",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                100L,
                "1.0.0",
                3,
                null,
                null);
        assertThatThrownBy(() -> service.publishRelease(badVersion, fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("release.version_invalid");

        // Invalid ring
        PublishReleaseCommand badRing = new PublishReleaseCommand(
                "1.2.0",
                "ANDROID",
                "path",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                100L,
                "1.0.0",
                4,
                null,
                null);
        assertThatThrownBy(() -> service.publishRelease(badRing, fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("device.ring_invalid");

        // Non-federation entity scope
        ScopeContext nonFed = SystemScope.own(UUID.fromString("0190a800-0000-7000-8000-000000000099"), null);
        PublishReleaseCommand valid = new PublishReleaseCommand(
                "1.2.0",
                "ANDROID",
                "path",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                100L,
                "1.0.0",
                3,
                null,
                null);
        assertThatThrownBy(() -> service.publishRelease(valid, nonFed))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("release.federation_only");
    }

    @Test
    void pauseAndResumeReleaseGovernsRolloutState() {
        UUID releaseId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq("1.2.0"), eq("ANDROID")))
                .thenReturn(List.of(Map.of("release_id", releaseId, "status", "PUBLISHED")));

        // Stub getRelease query for after pause
        ReleaseRecord pausedRecord = new ReleaseRecord(
                releaseId,
                systemEntity,
                "1.2.0",
                "ANDROID",
                "path",
                "hash",
                100L,
                "1.0.0",
                3,
                "PAUSED",
                now,
                "system",
                now,
                "system",
                "Pause reason");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("1.2.0"), eq("ANDROID")))
                .thenReturn(List.of(pausedRecord));

        ReleaseRecord paused = service.pauseRelease("1.2.0", "ANDROID", "Regression detected", fedScope);
        assertThat(paused.status()).isEqualTo("PAUSED");

        verify(audit)
                .record(
                        eq(DefaultCentralUpdateService.AUDIT_RELEASE_PAUSED),
                        any(),
                        any(),
                        any(),
                        eq(fedScope),
                        anyString());

        verify(events).publish(any(ReleasePaused.class));

        // When paused, pausing again fails
        when(jdbc.queryForList(anyString(), eq("1.2.0"), eq("ANDROID")))
                .thenReturn(List.of(Map.of("release_id", releaseId, "status", "PAUSED")));
        assertThatThrownBy(() -> service.pauseRelease("1.2.0", "ANDROID", "Again", fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("release.already_paused");

        // Resume release
        ReleaseRecord publishedRecord = new ReleaseRecord(
                releaseId,
                systemEntity,
                "1.2.0",
                "ANDROID",
                "path",
                "hash",
                100L,
                "1.0.0",
                3,
                "PUBLISHED",
                now,
                "system",
                null,
                null,
                "Notes");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("1.2.0"), eq("ANDROID")))
                .thenReturn(List.of(publishedRecord));

        ReleaseRecord resumed = service.resumeRelease("1.2.0", "ANDROID", fedScope);
        assertThat(resumed.status()).isEqualTo("PUBLISHED");

        verify(audit)
                .record(
                        eq(DefaultCentralUpdateService.AUDIT_RELEASE_RESUMED),
                        any(),
                        any(),
                        any(),
                        eq(fedScope),
                        anyString());

        verify(events).publish(any(ReleaseResumed.class));
    }

    @Test
    void assignDeviceRingValidatesAndPublishesEvent() {
        UUID deviceId = UUID.randomUUID();
        service.assignDeviceRing(deviceId, systemEntity, 1, fedScope);

        verify(audit)
                .record(
                        eq(DefaultCentralUpdateService.AUDIT_DEVICE_RING_ASSIGNED),
                        any(),
                        any(),
                        any(),
                        eq(fedScope),
                        anyString());

        ArgumentCaptor<DeviceRingAssigned> captor = ArgumentCaptor.forClass(DeviceRingAssigned.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue().deviceId()).isEqualTo(deviceId);
        assertThat(captor.getValue().ring()).isEqualTo(1);

        // Invalid ring fails
        assertThatThrownBy(() -> service.assignDeviceRing(deviceId, systemEntity, -1, fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("device.ring_invalid");
    }

    @Test
    void resolveTargetReleaseWithholdsPausedAndRespectsRings() {
        UUID deviceId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq(deviceId))).thenReturn(List.of(3)); // Ring 3 Fleet

        ReleaseRecord r1 = new ReleaseRecord(
                UUID.randomUUID(),
                systemEntity,
                "1.1.0",
                "ANDROID",
                "path1",
                "hash1",
                100L,
                "1.0.0",
                3,
                "PUBLISHED",
                now,
                "system",
                null,
                null,
                null);
        ReleaseRecord r2 = new ReleaseRecord(
                UUID.randomUUID(),
                systemEntity,
                "1.2.0",
                "ANDROID",
                "path2",
                "hash2",
                100L,
                "1.0.0",
                3,
                "PUBLISHED",
                now,
                "system",
                null,
                null,
                null);

        // Both published
        when(jdbc.query(anyString(), any(RowMapper.class), eq("ANDROID"), anyInt()))
                .thenReturn(List.of(r1, r2));

        Optional<ReleaseRecord> target = service.resolveTargetRelease(deviceId, "1.0.0", "ANDROID");
        assertThat(target).isPresent();
        assertThat(target.get().version()).isEqualTo("1.2.0");

        // When 1.2.0 is paused (so only 1.1.0 is returned by SQL where status = 'PUBLISHED')
        when(jdbc.query(anyString(), any(RowMapper.class), eq("ANDROID"), anyInt()))
                .thenReturn(List.of(r1));

        Optional<ReleaseRecord> targetPaused = service.resolveTargetRelease(deviceId, "1.0.0", "ANDROID");
        assertThat(targetPaused).isPresent();
        assertThat(targetPaused.get().version()).isEqualTo("1.1.0");

        // When device is already at 1.1.0, and 1.2.0 is paused, resolution returns empty (up to date)
        Optional<ReleaseRecord> targetUpToDate = service.resolveTargetRelease(deviceId, "1.1.0", "ANDROID");
        assertThat(targetUpToDate).isEmpty();
    }

    @Test
    void getDeviceUpdateManifestProducesVerifiableSignedManifest() {
        UUID deviceId = UUID.randomUUID();
        ReleaseRecord r = new ReleaseRecord(
                UUID.randomUUID(),
                systemEntity,
                "1.2.0",
                "ANDROID",
                "releases/android/1.2.0/package.bin",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                2048L,
                "1.0.0",
                3,
                "PUBLISHED",
                now,
                "system",
                null,
                null,
                null);

        when(jdbc.query(anyString(), any(RowMapper.class), eq("ANDROID"), anyInt()))
                .thenReturn(List.of(r));

        DeviceUpdateManifest manifest = service.getDeviceUpdateManifest(deviceId, "1.0.0", "ANDROID");

        assertThat(manifest.updateAvailable()).isTrue();
        assertThat(manifest.targetVersion()).isEqualTo("1.2.0");
        assertThat(manifest.packageHash()).isEqualTo(r.packageHash());
        assertThat(manifest.packageSizeBytes()).isEqualTo(2048L);
        assertThat(manifest.signature()).isNotNull();

        // Cryptographically verifiable
        assertThat(service.verifyManifest(manifest)).isTrue();

        // Tampering fails verification
        DeviceUpdateManifest tampered = new DeviceUpdateManifest(
                manifest.deviceId(),
                "1.3.0",
                manifest.platform(),
                manifest.packageUrl(),
                manifest.packageHash(),
                manifest.packageSizeBytes(),
                manifest.minSupportedVersion(),
                manifest.targetRing(),
                manifest.issuedAt(),
                manifest.signature(),
                manifest.keyId(),
                true);
        assertThat(service.verifyManifest(tampered)).isFalse();
    }
}
