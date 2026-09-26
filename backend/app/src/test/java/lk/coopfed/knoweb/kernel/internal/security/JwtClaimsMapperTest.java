package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The claim table of doc 19 section 1 as {@link JwtClaimsMapper} reads it. */
class JwtClaimsMapperTest {

    private static final UUID USER = UUID.fromString("0190a500-0000-7000-8000-000000000010");
    private static final UUID DEVICE = UUID.fromString("0190a500-0000-7000-8000-000000000020");
    private static final UUID HOME = UUID.fromString("0190a500-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190a500-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190a500-0000-7000-8000-000000000101");
    private static final UUID GRANTED = UUID.fromString("0190a500-0000-7000-8000-000000000003");

    /** M1's records, as the tests need them: the user holds a role at OTHER's shop and a grant on GRANTED. */
    private final UserScopes records = new UserScopes() {
        @Override
        public Set<Scope> scopesOf(UUID userId) {
            return USER.equals(userId) ? Set.of(new Scope(OTHER, SHOP)) : Set.of();
        }

        @Override
        public Set<UUID> grantsOf(UUID userId) {
            return USER.equals(userId) ? Set.of(GRANTED) : Set.of();
        }
    };

    /** M1's devices, as the tests need them: DEVICE is active at OTHER's SHOP. */
    private final DeviceScopes devices = (deviceId, correlation, locale) -> {
        if (!DEVICE.equals(deviceId)) {
            throw new ProblemException("sync.device_unknown");
        }
        Scope shop = new Scope(OTHER, SHOP);
        return new ScopeContext(
                null, deviceId, OTHER, List.of(shop), shop, PolicyClass.DEVICE, Set.of(), null, locale, correlation);
    };

    private final JwtClaimsMapper mapper = new JwtClaimsMapper(records, devices);

    @Test
    void aDeviceTokenIsTheDeviceAndItsShopNotAUser() {
        // A client-credentials token of the device's client: its subject is the provider's
        // service account, nobody the platform knows; the scope headers do not apply.
        Jwt jwt = jwt(Map.of("sub", USER.toString(), "dev", DEVICE.toString(), "cls", "DEVICE", "lang", "ta"));

        ScopeContext scope = mapper.map(jwt, HOME.toString(), null, null, Locale.ENGLISH);

        assertThat(scope.userId()).isNull();
        assertThat(scope.deviceId()).isEqualTo(DEVICE);
        assertThat(scope.policyClass()).isEqualTo(PolicyClass.DEVICE);
        assertThat(scope.activeScope()).isEqualTo(new Scope(OTHER, SHOP));
        assertThat(scope.lang()).isEqualTo("ta");
    }

    @Test
    void aDeviceTokenForADeviceM1DoesNotKnowIsRefused() {
        Jwt jwt = jwt(Map.of("sub", USER.toString(), "dev", UUID.randomUUID().toString(), "cls", "DEVICE"));

        assertThatThrownBy(() -> mapper.map(jwt, null, null, null, null))
                .isInstanceOf(ProblemException.class)
                .hasMessage("sync.device_unknown");
    }

    @Test
    void everyClaimOfTheTableLandsInTheContext() {
        Jwt jwt = jwt(Map.of(
                "sub", USER.toString(),
                "dev", DEVICE.toString(),
                "ent", HOME.toString(),
                "scopes", List.of(HOME.toString(), OTHER + "/" + SHOP),
                "cls", "OWN",
                "grants", List.of(OTHER.toString()),
                "mfa_at", 1_800_000_000L,
                "lang", "si",
                "roles", List.of("r1")));

        ScopeContext scope = mapper.map(jwt, OTHER.toString(), SHOP.toString(), null, Locale.ENGLISH);

        assertThat(scope.userId()).isEqualTo(USER);
        assertThat(scope.deviceId()).isEqualTo(DEVICE);
        assertThat(scope.homeEntityId()).isEqualTo(HOME);
        assertThat(scope.scopes()).containsExactly(new Scope(HOME, null), new Scope(OTHER, SHOP));
        assertThat(scope.activeScope()).isEqualTo(new Scope(OTHER, SHOP));
        assertThat(scope.policyClass()).isEqualTo(PolicyClass.OWN);
        // An OWN caller has no granted entities, whatever the token says.
        assertThat(scope.grantedEntities()).isEmpty();
        assertThat(scope.mfaAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
        assertThat(scope.lang()).isEqualTo("si");
        assertThat(scope.correlationId()).isNotNull();
    }

    @Test
    void thePlatformUserIdWinsOverTheProviderSubject() {
        Jwt jwt = jwt(Map.of("sub", "f3b1c0de-0000-4000-8000-000000000099", "uid", USER.toString()));
        assertThat(mapper.map(jwt, null, null, null, null).userId()).isEqualTo(USER);
    }

    @Test
    void withoutAScopesClaimTheAssignmentsAreTheScopesAndNothingElse() {
        Jwt jwt = jwt(Map.of("sub", USER.toString(), "ent", HOME.toString(), "cls", "OWN"));

        ScopeContext scope = mapper.map(jwt, null, null, null, null);

        // The home entity is where the user belongs, not where they may act: the shop only.
        assertThat(scope.scopes()).containsExactly(new Scope(OTHER, SHOP));
        assertThat(scope.activeScope()).isEqualTo(new Scope(OTHER, SHOP));
        assertThat(scope.homeEntityId()).isEqualTo(HOME);
    }

    @Test
    void anOwnUserWithNoAssignmentActsNowhere() {
        UUID stranger = UUID.fromString("0190a500-0000-7000-8000-000000000011");
        Jwt jwt = jwt(Map.of("sub", stranger.toString(), "ent", HOME.toString(), "cls", "OWN"));

        ScopeContext scope = mapper.map(jwt, null, null, null, null);

        assertThat(scope.scopes()).isEmpty();
        assertThat(scope.hasActiveScope()).isFalse();
    }

    @Test
    void aReadOnlyClassActsFromItsHomeEntity() {
        Jwt jwt = jwt(Map.of("sub", USER.toString(), "ent", HOME.toString(), "cls", "FEDERATION_VIEW"));

        ScopeContext scope = mapper.map(jwt, null, null, null, null);

        assertThat(scope.scopes()).containsExactly(new Scope(HOME, null));
        assertThat(scope.policyClass()).isEqualTo(PolicyClass.FEDERATION_VIEW);
    }

    @Test
    void theSecondFactorIsTheExplicitClaimOrAnAuthenticationThatUsedOne() {
        long at = 1_800_000_000L;
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "mfa_at", at)), null, null, null, null)
                        .mfaAt())
                .isEqualTo(Instant.ofEpochSecond(at));
        // auth_time counts when acr (or amr) says a second factor was used ...
        assertThat(mapper.map(
                                jwt(Map.of("sub", USER.toString(), "auth_time", at, "acr", "loa2")),
                                null,
                                null,
                                null,
                                null)
                        .mfaAt())
                .isEqualTo(Instant.ofEpochSecond(at));
        assertThat(mapper.map(
                                jwt(Map.of("sub", USER.toString(), "auth_time", at, "amr", List.of("pwd", "otp"))),
                                null,
                                null,
                                null,
                                null)
                        .mfaAt())
                .isEqualTo(Instant.ofEpochSecond(at));
        // ... and not for a password alone.
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "auth_time", at, "acr", "1")), null, null, null, null)
                        .mfaAt())
                .isNull();
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "auth_time", at)), null, null, null, null)
                        .mfaAt())
                .isNull();
        // A development realm without OTP: a fresh password sign-in is the step-up.
        JwtClaimsMapper development = new JwtClaimsMapper(records, devices, List.of("loa2"), true);
        assertThat(development
                        .map(jwt(Map.of("sub", USER.toString(), "auth_time", at)), null, null, null, null)
                        .mfaAt())
                .isEqualTo(Instant.ofEpochSecond(at));
    }

    @Test
    void onlyALocalOrTestIssuerIsADevelopmentIssuer() {
        // password-reauth-counts with any other issuer is logged at ERROR when the mapper starts.
        assertThat(JwtClaimsMapper.isDevelopmentIssuer("http://localhost:8085/realms/coop"))
                .isTrue();
        assertThat(JwtClaimsMapper.isDevelopmentIssuer("http://127.0.0.1:8085/realms/coop"))
                .isTrue();
        assertThat(JwtClaimsMapper.isDevelopmentIssuer("http://provider.test/realms/coop"))
                .isTrue();
        assertThat(JwtClaimsMapper.isDevelopmentIssuer("https://id.coopfed.lk/realms/coop"))
                .isFalse();
        assertThat(JwtClaimsMapper.isDevelopmentIssuer("https://localhost.evil.lk/realms/coop"))
                .isFalse();
        assertThat(JwtClaimsMapper.isDevelopmentIssuer("")).isFalse();
        assertThat(JwtClaimsMapper.isDevelopmentIssuer(null)).isFalse();
    }

    @Test
    void aGrantsClaimNarrowsTheRecordedGrantsAndNeverWidensThem() {
        Jwt wider = jwt(Map.of(
                "sub",
                USER.toString(),
                "cls",
                "EXTERNAL_TIMEBOXED",
                "grants",
                List.of(GRANTED.toString(), OTHER.toString())));
        assertThat(mapper.map(wider, null, null, null, null).grantedEntities()).containsExactly(GRANTED);
        Jwt none = jwt(Map.of("sub", USER.toString(), "cls", "EXTERNAL_TIMEBOXED", "grants", List.of()));
        assertThat(mapper.map(none, null, null, null, null).grantedEntities()).isEmpty();
    }

    @Test
    void anExternalCallerWithoutAGrantsClaimGetsTheRecordedGrants() {
        Jwt external = jwt(Map.of("sub", USER.toString(), "cls", "EXTERNAL_TIMEBOXED"));
        assertThat(mapper.map(external, null, null, null, null).grantedEntities())
                .containsExactly(GRANTED);
        // Any other class carries no grants, whatever the records say.
        Jwt own = jwt(Map.of("sub", USER.toString(), "ent", HOME.toString(), "cls", "OWN"));
        assertThat(mapper.map(own, null, null, null, null).grantedEntities()).isEmpty();
    }

    @Test
    void anUnknownOrMissingClassIsNoneAndShowsNothing() {
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "cls", "SUPERUSER")), null, null, null, null)
                        .policyClass())
                .isEqualTo(PolicyClass.NONE);
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString())), null, null, null, null)
                        .policyClass())
                .isEqualTo(PolicyClass.NONE);
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "cls", "federation_view")), null, null, null, null)
                        .policyClass())
                .isEqualTo(PolicyClass.FEDERATION_VIEW);
    }

    @Test
    void theLanguageIsTheTokensThenTheRequestsThenEnglish() {
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "lang", "ta")), null, null, null, Locale.ENGLISH)
                        .lang())
                .isEqualTo("ta");
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString())), null, null, null, Locale.forLanguageTag("si"))
                        .lang())
                .isEqualTo("si");
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString(), "lang", "fr")), null, null, null, Locale.FRENCH)
                        .lang())
                .isEqualTo("en");
    }

    @Test
    void theCorrelationHeaderIsKeptAndAMissingOneIsMinted() {
        UUID correlation = UUID.fromString("0190a500-0000-7000-8000-0000000000c0");
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString())), null, null, correlation.toString(), null)
                        .correlationId())
                .isEqualTo(correlation);
        assertThat(mapper.map(jwt(Map.of("sub", USER.toString())), null, null, null, null)
                        .correlationId())
                .isNotNull();
    }

    @Test
    void aSubjectThatIsNotAUserIdIsRefused() {
        assertThatThrownBy(() -> mapper.map(
                        jwt(Map.of("sub", "service-account-x", "ent", HOME.toString())), null, null, null, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("scope.invalid");
        assertThatThrownBy(() -> mapper.map(jwt(Map.of("ent", HOME.toString())), null, null, null, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("token.invalid");
    }

    @Test
    void aScopeHeaderThatIsNotAUuidIsRefused() {
        assertThatThrownBy(() -> mapper.map(jwt(Map.of("sub", USER.toString())), "the-federation", null, null, null))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("scope.invalid");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
