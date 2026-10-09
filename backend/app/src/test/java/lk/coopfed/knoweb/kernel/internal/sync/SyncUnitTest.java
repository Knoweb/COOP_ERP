package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

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
    void withoutAConfiguredKeyAndNotRequiredAnInstanceMakesItsOwn() {
        TillSigner one = new TillSigner("", "", false);
        TillSigner other = new TillSigner("", "", false);
        assertThat(one.keyId()).isNotEqualTo(other.keyId());
        assertThatThrownBy(() -> new TillSigner("bm90IGEga2V5", "bm90IGEga2V5", false))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Wave 2, TWK-27: fail closed; a blank key stops the start and names the properties. */
    @Test
    void aRequiredKeyThatIsBlankStopsTheStart() {
        assertThatThrownBy(() -> new TillSigner("", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coop-erp.sync.signing.private-key")
                .hasMessageContaining("coop-erp.sync.signing.public-key");
        assertThatThrownBy(() -> new TillSigner("  ", null, true)).isInstanceOf(IllegalStateException.class);
    }

    /** The default of application.yml is the fail-closed one; only the test resources say false. */
    @Test
    void theApplicationRequiresTheKeyAndEnforcesPermissionsUnlessTheTestsSayOtherwise() throws Exception {
        org.springframework.core.env.MutablePropertySources sources =
                new org.springframework.core.env.MutablePropertySources();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        org.springframework.core.env.PropertySourcesPropertyResolver main =
                new org.springframework.core.env.PropertySourcesPropertyResolver(sources);
        assertThat(main.getProperty("coop-erp.sync.signing.required")).isEqualTo("true");
        assertThat(main.getProperty("coop-erp.security.enforce-permissions")).isEqualTo("true");
        assertThat(main.getProperty("coop-erp.sync.max-uncompressed-bytes")).isEqualTo("16777216");
    }

    @Test
    void theDocumentBundlesAreKnownByTypeWhateverTheirVersion() {
        assertThat(EventApplier.isBundle("receipt.issued.v1")).isTrue();
        assertThat(EventApplier.isBundle("receipt.issued.v2")).isTrue();
        assertThat(EventApplier.isBundle("grn.confirmed.v1")).isTrue();
        assertThat(EventApplier.isBundle("till_session.opened.v1")).isFalse();
        assertThat(EventApplier.isBundle("audit.mode_switch.v1")).isFalse();
    }

    /**
     * A quarantined bundle explains its gap only when its document type lists the bundle's event
     * (kernel.numbering_gaps, V0089), so every bundle type must belong to exactly one offline
     * document type in the seed, or its quarantined numbers are reported as missing every night.
     */
    @Test
    void everyBundleTypeBelongsToExactlyOneOfflineDocumentType() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ClassPathResource("seed/kernel/document-types.yaml"));
        List<?> types = (List<?>) factory.getObject().get("document_types");

        Map<String, List<String>> owners = new HashMap<>();
        for (Object entry : types) {
            Map<?, ?> type = (Map<?, ?>) entry;
            if (type.get("allowed_sync_events") instanceof List<?> events) {
                assertThat(type.get("offline_issuable"))
                        .as("%s lists sync events but is not offline issuable", type.get("code"))
                        .isEqualTo(true);
                for (Object event : events) {
                    owners.computeIfAbsent(event.toString(), e -> new ArrayList<>())
                            .add(type.get("code").toString());
                }
            }
        }

        for (String bundle : EventApplier.BUNDLE_TYPES) {
            assertThat(owners.getOrDefault(bundle, List.of()))
                    .as("document types listing the bundle %s", bundle)
                    .hasSize(1);
        }
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
