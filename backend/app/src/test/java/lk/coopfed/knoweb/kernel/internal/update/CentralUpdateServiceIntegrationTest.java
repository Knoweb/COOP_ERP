package lk.coopfed.knoweb.kernel.internal.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CentralUpdateService;
import lk.coopfed.knoweb.kernel.api.DeviceRingAssigned;
import lk.coopfed.knoweb.kernel.api.DeviceUpdateManifest;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.PublishReleaseCommand;
import lk.coopfed.knoweb.kernel.api.ReleasePaused;
import lk.coopfed.knoweb.kernel.api.ReleasePublished;
import lk.coopfed.knoweb.kernel.api.ReleaseRecord;
import lk.coopfed.knoweb.kernel.api.ReleaseResumed;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(MemoryObjectStore.class)
class CentralUpdateServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    CentralUpdateService updateService;

    @Autowired
    PackageStore packageStore;

    @Autowired
    ReleaseSigner releaseSigner;

    @Autowired
    SystemScope systemScope;

    @Autowired
    KernelRecorder kernel;

    private ScopeContext fedScope;
    private final UUID deviceId = UUID.fromString("0190a800-0000-7000-8000-000000000301");

    @BeforeEach
    void cleanTables() {
        superuserJdbc().update("delete from kernel.app_release");
        superuserJdbc().update("delete from kernel.device_rollout_ring");
        theDatabaseNamesTheFederation(TEST_FEDERATION);
        fedScope = SystemScope.own(TEST_FEDERATION, null);
    }

    @Test
    void publishReleaseReachesTargetVersionInSignedManifest() {
        byte[] apkBytes = "dummy apk content v1.1.0".getBytes(StandardCharsets.UTF_8);
        PackageStore.StoredPackage pkg = packageStore.store("ANDROID", "1.1.0", apkBytes);

        PublishReleaseCommand cmd = new PublishReleaseCommand(
                "1.1.0",
                "ANDROID",
                pkg.packagePath(),
                pkg.sha256Hex(),
                pkg.sizeBytes(),
                "1.0.0",
                3,
                "Release 1.1.0 initial",
                Map.of("channel", "stable"));

        ReleaseRecord release = updateService.publishRelease(cmd, fedScope);

        assertThat(release.version()).isEqualTo("1.1.0");
        assertThat(release.status()).isEqualTo("PUBLISHED");

        assertThat(kernel.committedAudit())
                .anyMatch(a -> DefaultCentralUpdateService.AUDIT_RELEASE_PUBLISHED.equals(a.eventType()));
        assertThat(kernel.committedEvents())
                .anyMatch(e -> e instanceof ReleasePublished rp && "1.1.0".equals(rp.version()));

        DeviceUpdateManifest manifest = updateService.getDeviceUpdateManifest(deviceId, "1.0.0", "ANDROID");

        assertThat(manifest.updateAvailable()).isTrue();
        assertThat(manifest.targetVersion()).isEqualTo("1.1.0");
        assertThat(manifest.platform()).isEqualTo("ANDROID");
        assertThat(manifest.packageHash()).isEqualTo(pkg.sha256Hex());
        assertThat(manifest.packageSizeBytes()).isEqualTo(pkg.sizeBytes());
        assertThat(manifest.signature()).isNotNull().isNotBlank();

        assertThat(updateService.verifyManifest(manifest)).isTrue();
    }

    @Test
    void manifestSignatureIsVerifiableAndTamperEvident() {
        byte[] apkBytes = "dummy apk content v1.0.5".getBytes(StandardCharsets.UTF_8);
        PackageStore.StoredPackage pkg = packageStore.store("ANDROID", "1.0.5", apkBytes);

        updateService.publishRelease(
                new PublishReleaseCommand(
                        "1.0.5",
                        "ANDROID",
                        pkg.packagePath(),
                        pkg.sha256Hex(),
                        pkg.sizeBytes(),
                        "1.0.0",
                        3,
                        null,
                        null),
                fedScope);

        DeviceUpdateManifest manifest = updateService.getDeviceUpdateManifest(deviceId, "1.0.0", "ANDROID");
        assertThat(updateService.verifyManifest(manifest)).isTrue();

        DeviceUpdateManifest tampered = new DeviceUpdateManifest(
                manifest.deviceId(),
                "2.0.0",
                manifest.platform(),
                manifest.packageUrl(),
                manifest.packageHash(),
                manifest.packageSizeBytes(),
                manifest.minSupportedVersion(),
                manifest.targetRing(),
                manifest.issuedAt(),
                manifest.signature(),
                manifest.keyId(),
                manifest.updateAvailable());
        assertThat(updateService.verifyManifest(tampered)).isFalse();

        DeviceUpdateManifest tamperedHash = new DeviceUpdateManifest(
                manifest.deviceId(),
                manifest.targetVersion(),
                manifest.platform(),
                manifest.packageUrl(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                manifest.packageSizeBytes(),
                manifest.minSupportedVersion(),
                manifest.targetRing(),
                manifest.issuedAt(),
                manifest.signature(),
                manifest.keyId(),
                manifest.updateAvailable());
        assertThat(updateService.verifyManifest(tamperedHash)).isFalse();
    }

    @Test
    void pauseControlGovernsTargetVersionResolution() {
        byte[] apk1 = "apk 1.1.0".getBytes(StandardCharsets.UTF_8);
        byte[] apk2 = "apk 1.2.0".getBytes(StandardCharsets.UTF_8);
        PackageStore.StoredPackage pkg1 = packageStore.store("ANDROID", "1.1.0", apk1);
        PackageStore.StoredPackage pkg2 = packageStore.store("ANDROID", "1.2.0", apk2);

        updateService.publishRelease(
                new PublishReleaseCommand(
                        "1.1.0",
                        "ANDROID",
                        pkg1.packagePath(),
                        pkg1.sha256Hex(),
                        pkg1.sizeBytes(),
                        "1.0.0",
                        3,
                        null,
                        null),
                fedScope);
        updateService.publishRelease(
                new PublishReleaseCommand(
                        "1.2.0",
                        "ANDROID",
                        pkg2.packagePath(),
                        pkg2.sha256Hex(),
                        pkg2.sizeBytes(),
                        "1.0.0",
                        3,
                        null,
                        null),
                fedScope);

        DeviceUpdateManifest m1 = updateService.getDeviceUpdateManifest(deviceId, "1.0.0", "ANDROID");
        assertThat(m1.updateAvailable()).isTrue();
        assertThat(m1.targetVersion()).isEqualTo("1.2.0");

        ReleaseRecord paused = updateService.pauseRelease("1.2.0", "ANDROID", "Regression found", fedScope);
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(kernel.committedAudit())
                .anyMatch(a -> DefaultCentralUpdateService.AUDIT_RELEASE_PAUSED.equals(a.eventType()));
        assertThat(kernel.committedEvents())
                .anyMatch(e -> e instanceof ReleasePaused rp && "1.2.0".equals(rp.version()));

        DeviceUpdateManifest m2 = updateService.getDeviceUpdateManifest(deviceId, "1.0.0", "ANDROID");
        assertThat(m2.updateAvailable()).isTrue();
        assertThat(m2.targetVersion()).isEqualTo("1.1.0");

        DeviceUpdateManifest m3 = updateService.getDeviceUpdateManifest(deviceId, "1.1.0", "ANDROID");
        assertThat(m3.updateAvailable()).isFalse();

        ReleaseRecord resumed = updateService.resumeRelease("1.2.0", "ANDROID", fedScope);
        assertThat(resumed.status()).isEqualTo("PUBLISHED");
        assertThat(kernel.committedAudit())
                .anyMatch(a -> DefaultCentralUpdateService.AUDIT_RELEASE_RESUMED.equals(a.eventType()));
        assertThat(kernel.committedEvents())
                .anyMatch(e -> e instanceof ReleaseResumed rr && "1.2.0".equals(rr.version()));

        DeviceUpdateManifest m4 = updateService.getDeviceUpdateManifest(deviceId, "1.1.0", "ANDROID");
        assertThat(m4.updateAvailable()).isTrue();
        assertThat(m4.targetVersion()).isEqualTo("1.2.0");
    }

    @Test
    void rolloutRingsGovernTargetVersionResolution() {
        byte[] apk = "apk 1.3.0".getBytes(StandardCharsets.UTF_8);
        PackageStore.StoredPackage pkg = packageStore.store("ANDROID", "1.3.0", apk);

        updateService.publishRelease(
                new PublishReleaseCommand(
                        "1.3.0",
                        "ANDROID",
                        pkg.packagePath(),
                        pkg.sha256Hex(),
                        pkg.sizeBytes(),
                        "1.0.0",
                        1,
                        null,
                        null),
                fedScope);

        UUID canaryDevice = UUID.fromString("0190a800-0000-7000-8000-000000000302");
        UUID fleetDevice = UUID.fromString("0190a800-0000-7000-8000-000000000303");

        updateService.assignDeviceRing(canaryDevice, TEST_FEDERATION, 1, fedScope);

        assertThat(kernel.committedAudit())
                .anyMatch(a -> DefaultCentralUpdateService.AUDIT_DEVICE_RING_ASSIGNED.equals(a.eventType()));
        assertThat(kernel.committedEvents())
                .anyMatch(e ->
                        e instanceof DeviceRingAssigned dra && canaryDevice.equals(dra.deviceId()) && dra.ring() == 1);

        Optional<ReleaseRecord> targetCanary = updateService.resolveTargetRelease(canaryDevice, "1.0.0", "ANDROID");
        assertThat(targetCanary).isPresent();
        assertThat(targetCanary.get().version()).isEqualTo("1.3.0");

        Optional<ReleaseRecord> targetFleet = updateService.resolveTargetRelease(fleetDevice, "1.0.0", "ANDROID");
        assertThat(targetFleet).isEmpty();
    }

    @Test
    void guardsRefuseInvalidInputsAndDisallowedScopes() {
        ScopeContext nonFedScope = SystemScope.own(UUID.fromString("0190a800-0000-7000-8000-000000000002"), null);

        PublishReleaseCommand cmd = new PublishReleaseCommand(
                "1.0.0",
                "ANDROID",
                "path",
                "0000000000000000000000000000000000000000000000000000000000000000",
                100L,
                "1.0.0",
                3,
                null,
                null);

        assertThatThrownBy(() -> updateService.publishRelease(cmd, nonFedScope)).isInstanceOf(ProblemException.class);

        PublishReleaseCommand badVersion = new PublishReleaseCommand(
                "invalid-semver",
                "ANDROID",
                "path",
                "0000000000000000000000000000000000000000000000000000000000000000",
                100L,
                "1.0.0",
                3,
                null,
                null);
        assertThatThrownBy(() -> updateService.publishRelease(badVersion, fedScope))
                .isInstanceOf(ProblemException.class);

        PublishReleaseCommand badRing = new PublishReleaseCommand(
                "1.0.0",
                "ANDROID",
                "path",
                "0000000000000000000000000000000000000000000000000000000000000000",
                100L,
                "1.0.0",
                5,
                null,
                null);
        assertThatThrownBy(() -> updateService.publishRelease(badRing, fedScope))
                .isInstanceOf(ProblemException.class);
    }
}
