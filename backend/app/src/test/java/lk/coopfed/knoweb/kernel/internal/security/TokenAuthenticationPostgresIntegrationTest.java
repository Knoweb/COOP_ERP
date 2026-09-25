package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The resource server of 19A section 2 with permissions enforced, so that the user a command
 * runs as is visible: the token's user holds the role, another user does not.
 *
 * <p>What it proves: a valid token runs the command as the token's user; a token that names
 * no home entity still acts where M1's assignments put the user (the scopes come from the
 * records); a token signed by another key, an expired one, one from another issuer and one
 * that is not a JWT are refused with 401 {@code token.invalid} as a problem document; no
 * token at all is 401 {@code auth.required}; health needs no token.
 */
class TokenAuthenticationPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a600-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190a600-0000-7000-8000-000000000010");
    private static final UUID STRANGER = UUID.fromString("0190a600-0000-7000-8000-000000000011");
    private static final UUID ROLE = UUID.fromString("0190a600-0000-7000-8000-000000000201");

    @DynamicPropertySource
    static void enforce(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.security.enforce-permissions", () -> "true");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcPermissionResolver resolver;

    @Autowired
    JdbcUserScopes userScopes;

    @BeforeEach
    void theTokensUserHoldsTheRoleAndTheStrangerDoesNot() {
        JdbcTemplate admin = superuserJdbc();
        clean(admin);
        admin.update(
                "insert into security.permission (permission_code, module, description_en, offline_allowed, requires_mfa, scope)"
                        + " values ('hello.greeting.register', 'hello', 'Register a greeting', false, false, 'ENTITY')"
                        + " on conflict (permission_code) do nothing");
        for (UUID user : new UUID[] {USER, STRANGER}) {
            admin.update(
                    "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                            + " values (?, ?, ?, 'Token test user', 'BACK_OFFICE', 'ACTIVE')",
                    user,
                    ENTITY,
                    "u-" + user);
        }
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Greeter', false, 'OWN', 'ACTIVE')",
                ROLE,
                ENTITY);
        admin.update(
                "insert into security.role_permission (role_id, permission_code) values (?, 'hello.greeting.register')",
                ROLE);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, null)",
                USER,
                ROLE,
                ENTITY);
        resolver.invalidateAll();
        userScopes.invalidateAll();
    }

    @AfterEach
    void leaveTheCatalogueAsItWas() {
        // The seed loader test counts the catalogue: the hello permission goes again.
        JdbcTemplate admin = superuserJdbc();
        clean(admin);
        admin.execute("delete from security.role_permission where permission_code = 'hello.greeting.register'");
        admin.execute("delete from security.permission where permission_code = 'hello.greeting.register'");
    }

    private static void clean(JdbcTemplate admin) {
        admin.execute("delete from security.user_role where user_id in ('" + USER + "', '" + STRANGER + "')");
        admin.execute("delete from security.role_permission where role_id = '" + ROLE + "'");
        admin.execute("delete from security.role where role_id = '" + ROLE + "'");
        admin.execute("delete from security.app_user where user_id in ('" + USER + "', '" + STRANGER + "')");
    }

    @Test
    void aValidTokenRunsTheCommandAsTheTokensUser() {
        ResponseEntity<JsonNode> allowed = register(headers -> headers.setBearerAuth(token(USER, c -> {})));
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = register(headers -> headers.setBearerAuth(token(STRANGER, c -> {})));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("permission.denied");
    }

    @Test
    void aTokenWithoutAHomeEntityActsWhereTheAssignmentsPutTheUser() {
        // No ent claim: the only scope is the one M1's user_role holds, and it is the active one.
        ResponseEntity<JsonNode> response =
                register(headers -> headers.setBearerAuth(TestIdentityProvider.token(USER, null, "OWN")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefusedAsAProblemDocument() {
        ResponseEntity<JsonNode> response =
                register(headers -> headers.setBearerAuth(TestIdentityProvider.tokenFromAnotherKey(USER, ENTITY)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        assertThat(response.getBody().get("code").asText()).isEqualTo("token.invalid");
        assertThat(response.getBody().get("title").asText())
                .isEqualTo("Your sign-in is not valid or has expired; sign in again");
    }

    @Test
    void anExpiredTokenAndOneFromAnotherIssuerAreRefused() {
        ResponseEntity<JsonNode> expired = register(headers -> headers.setBearerAuth(
                token(USER, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(600))))));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getBody().get("code").asText()).isEqualTo("token.invalid");

        ResponseEntity<JsonNode> elsewhere =
                register(headers -> headers.setBearerAuth(token(USER, c -> c.issuer("http://elsewhere.test"))));
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(elsewhere.getBody().get("code").asText()).isEqualTo("token.invalid");
    }

    @Test
    void aTokenOfAnotherClientOfTheRealmIsRefused() {
        // The provider's own admin-cli, a service account: not a client of this platform.
        ResponseEntity<JsonNode> response =
                register(headers -> headers.setBearerAuth(token(USER, c -> c.claim("azp", "admin-cli"))));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("token.invalid");
    }

    @Test
    void anEntityWideHolderMayActAtALocationOfTheEntity() {
        // Doc 19 section 3.1: an entity grant is expanded to every location; the shell may send one.
        UUID shop = UUID.fromString("0190a600-0000-7000-8000-000000000101");
        ResponseEntity<JsonNode> response = register(headers -> {
            headers.setBearerAuth(token(USER, c -> {}));
            headers.set("X-Scope-Location", shop.toString());
        });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // A location of an entity the caller does not hold is still refused.
        ResponseEntity<JsonNode> elsewhere = register(headers -> {
            headers.setBearerAuth(token(USER, c -> {}));
            headers.set("X-Scope-Entity", UUID.randomUUID().toString());
            headers.set("X-Scope-Location", shop.toString());
        });
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(elsewhere.getBody().get("code").asText()).isEqualTo("scope.invalid");
    }

    @Test
    void aTokenThatIsNotAJwtIsRefusedTheSameWay() {
        ResponseEntity<JsonNode> response = register(headers -> headers.setBearerAuth("not.a.token"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("token.invalid");
    }

    @Test
    void withoutATokenTheApiRefusesAndSaysToSignIn() {
        ResponseEntity<JsonNode> command = register(headers -> {});
        assertThat(command.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(command.getBody().get("code").asText()).isEqualTo("auth.required");
        assertThat(command.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");

        ResponseEntity<JsonNode> query = http.getForEntity("/v1/hello/greetings", JsonNode.class);
        assertThat(query.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(query.getBody().get("code").asText()).isEqualTo("auth.required");
    }

    @Test
    void healthNeedsNoToken() {
        ResponseEntity<JsonNode> health = http.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<JsonNode> register(Consumer<HttpHeaders> customise) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Scope-Entity", ENTITY.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        customise.accept(headers);
        return http.exchange(
                "/v1/hello/greetings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("textEn", "Token " + UUID.randomUUID()), headers),
                JsonNode.class);
    }

    private static String token(UUID user, Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> customise) {
        return TestIdentityProvider.token(user, ENTITY, "OWN", customise);
    }
}
