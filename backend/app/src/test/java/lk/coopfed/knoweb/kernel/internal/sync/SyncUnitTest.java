package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Locale;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;

/**
 * The pieces of the sync gateway that need no database: the application floor, the signer, the
 * code alphabet and what is a bundle. The principal rule of the scope filter is proved in
 * {@code lk.coopfed.knoweb.kernel.internal.ScopeFilterPrincipalTest}.
 */
class SyncUnitTest {

    @Test
    void theFloorComparesDottedNumbersAndIgnoresWhatFollows() {
        assertThat(AppVersions.atLeast("1.4.2", "1.4.2")).isTrue();
        assertThat(AppVersions.atLeast("1.4", "1.4.0")).isTrue();
        assertThat(AppVersions.atLeast("1.10.0", "1.9.9")).isTrue();
        assertThat(AppVersions.atLeast("1.4.1", "1.4.2")).isFalse();
        assertThat(AppVersions.atLeast("1.4.2-rc1", "1.4.2")).isTrue();
        assertThat(AppVersions.atLeast("2.0.0+build7", "1.99")).isTrue();
        assertThat(AppVersions.atLeast("unknown", "0.0.0")).isTrue();
        assertThat(AppVersions.atLeast("unknown", "0.0.1")).isFalse();
        assertThat(AppVersions.atLeast(null, "1.0")).isFalse();
    }

    @Test
    void aConfiguredKeySignsAndItsPublicHalfVerifies() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TillSigner signer = new TillSigner(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));

        String signature = signer.sign("type=REVOKE\n");

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update("type=REVOKE\n".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signature))).isTrue();
        assertThat(signer.publicKeyBase64())
                .isEqualTo(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        assertThat(signer.keyId()).hasSize(16);
    }

    @Test
    void withoutAConfiguredKeyAnInstanceMakesItsOwn() {
        TillSigner one = new TillSigner("", "");
        TillSigner other = new TillSigner("", "");
        assertThat(one.keyId()).isNotEqualTo(other.keyId());
        assertThatThrownBy(() -> new TillSigner("bm90IGEga2V5", "bm90IGEga2V5"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDocumentBundlesAreKnownByTypeWhateverTheirVersion() {
        assertThat(EventApplier.isBundle("receipt.issued.v1")).isTrue();
        assertThat(EventApplier.isBundle("receipt.issued.v2")).isTrue();
        assertThat(EventApplier.isBundle("grn.confirmed.v1")).isTrue();
        assertThat(EventApplier.isBundle("till_session.opened.v1")).isFalse();
        assertThat(EventApplier.isBundle("audit.mode_switch.v1")).isFalse();
    }

    @Test
    void anEnrolmentCodeIsReadAsTyped() {
        assertThat(EnrolmentStore.hash("abcd-efgh ijkl-mnpq")).isEqualTo(EnrolmentStore.hash("ABCDEFGHIJKLMNPQ"));
        assertThat(EnrolmentStore.hash("ABCDEFGHIJKLMNPQ")).isNotEqualTo(EnrolmentStore.hash("ABCDEFGHIJKLMNPR"));
    }

    @Test
    void theRevokeIsSignedOverFourLines() {
        assertThat(DeviceAuth.canonical(
                        "REVOKE",
                        java.util.UUID.fromString("0190a900-0000-7000-8000-000000000001"),
                        "SUSPENDED",
                        java.time.Instant.parse("2026-09-25T04:30:00Z")))
                .isEqualTo("type=REVOKE\ndevice_id=0190a900-0000-7000-8000-000000000001\nstatus=SUSPENDED\n"
                        + "issued_at=2026-09-25T04:30:00Z\n");
    }

    @Test
    void anUnknownDeviceIsRefusedBeforeAnythingElse() {
        DeviceAuth auth = new DeviceAuth(
                new DeviceDirectory(null, null) {
                    @Override
                    java.util.Optional<DeviceRecord> find(java.util.UUID deviceId) {
                        return java.util.Optional.empty();
                    }
                },
                new TillSigner("", ""),
                java.time.Clock.systemUTC());
        assertThatThrownBy(() -> auth.scopeOf(Ids.next(), Ids.next(), Locale.ENGLISH))
                .isInstanceOf(ProblemException.class)
                .hasMessage("sync.device_unknown");
        assertThatThrownBy(() -> auth.scopeOf(null, Ids.next(), Locale.ENGLISH))
                .isInstanceOf(ProblemException.class)
                .hasMessage("token.invalid");
    }
}
