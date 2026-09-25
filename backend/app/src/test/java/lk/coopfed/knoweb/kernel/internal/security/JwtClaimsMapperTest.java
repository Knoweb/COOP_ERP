package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final JwtClaimsMapper mapper = new JwtClaimsMapper();

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
        assertThat(scope.grantedEntities()).containsExactly(OTHER);
        assertThat(scope.mfaAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
        assertThat(scope.lang()).isEqualTo("si");
        assertThat(scope.correlationId()).isNotNull();
    }

    @Test
    void withoutAScopesClaimTheHomeEntityIsTheOnlyScopeAndTheActiveOne() {
        Jwt jwt = jwt(Map.of("sub", USER.toString(), "ent", HOME.toString(), "cls", "OWN"));

        ScopeContext scope = mapper.map(jwt, null, null, null, null);

        assertThat(scope.scopes()).containsExactly(new Scope(HOME, null));
        assertThat(scope.activeScope()).isEqualTo(new Scope(HOME, null));
        assertThat(scope.entityId()).isEqualTo(HOME);
    }

    @Test
    void theHeaderChoosesAmongSeveralScopesAndNoHeaderChoosesNone() {
        Jwt jwt = jwt(Map.of(
                "sub",
                USER.toString(),
                "ent",
                HOME.toString(),
                "scopes",
                List.of(HOME.toString(), OTHER.toString()),
                "cls",
                "OWN"));

        assertThat(mapper.map(jwt, OTHER.toString(), null, null, null).activeScope())
                .isEqualTo(new Scope(OTHER, null));
        // The scope filter then asks the caller to choose (scope.required).
        assertThat(mapper.map(jwt, null, null, null, null).hasActiveScope()).isFalse();
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
