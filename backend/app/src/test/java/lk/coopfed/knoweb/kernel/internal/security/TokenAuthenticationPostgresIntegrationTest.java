package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
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
 * The resource server of 19A section 2 against a provider of this test's own: a key pair, a
 * JWKS served from a local socket, tokens signed here. With permissions enforced, the user a
 * command runs as is visible: the token's user holds the role, another user does not.
 *
 * <p>What it proves: a valid token runs the command as the token's user, and the user of the
 * token wins over the development header when both are sent; a token signed by another key,
 * an expired one and one from another issuer are refused with 401 {@code token.invalid} as a
 * problem document; a request without a token still takes the development headers (the first
 * K-02 pull request; the second turns this off); health needs no token.
 */
class TokenAuthenticationPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a600-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190a600-0000-7000-8000-000000000010");
    private static final UUID STRANGER = UUID.fromString("0190a600-0000-7000-8000-000000000011");
    private static final UUID ROLE = UUID.fromString("0190a600-0000-7000-8000-000000000201");
    private static final String ISSUER = "http://provider.test/realms/coop";

    private static RSAKey key;
    private static RSAKey otherKey;
    private static HttpServer jwks;

    @DynamicPropertySource
    static void aProviderOfOurOwn(DynamicPropertyRegistry registry) throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
        String jwkSet = new JWKSet(key.toPublicJWK()).toString();

        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/certs", exchange -> {
            byte[] body = jwkSet.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        jwks.start();

        registry.add("coop-erp.security.oidc.issuer", () -> ISSUER);
        registry.add(
                "coop-erp.security.oidc.jwk-set-uri",
                () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs");
        registry.add("coop-erp.security.enforce-permissions", () -> "true");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcPermissionResolver resolver;

    @BeforeEach
    void theTokensUserHoldsTheRoleAndTheStrangerDoesNot() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from security.user_role where user_id in ('" + USER + "', '" + STRANGER + "')");
        admin.execute("delete from security.role_permission where role_id = '" + ROLE + "'");
        admin.execute("delete from security.role where role_id = '" + ROLE + "'");
        admin.execute("delete from security.app_user where user_id in ('" + USER + "', '" + STRANGER + "')");
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
    }

    @AfterEach
    void leaveTheCatalogueAsItWas() {
        // The seed loader test counts the catalogue: the hello permission goes again.
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from security.user_role where user_id in ('" + USER + "', '" + STRANGER + "')");
        admin.execute("delete from security.role_permission where role_id = '" + ROLE + "'");
        admin.execute("delete from security.role where role_id = '" + ROLE + "'");
        admin.execute("delete from security.app_user where user_id in ('" + USER + "', '" + STRANGER + "')");
        admin.execute("delete from security.role_permission where permission_code = 'hello.greeting.register'");
        admin.execute("delete from security.permission where permission_code = 'hello.greeting.register'");
    }

    @Test
    void aValidTokenRunsTheCommandAsTheTokensUser() {
        ResponseEntity<JsonNode> allowed = register(headers -> headers.setBearerAuth(token(key, USER, c -> {})));
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = register(headers -> headers.setBearerAuth(token(key, STRANGER, c -> {})));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("permission.denied");
    }

    @Test
    void theTokenWinsOverTheDevelopmentHeader() {
        ResponseEntity<JsonNode> response = register(headers -> {
            headers.setBearerAuth(token(key, USER, c -> {}));
            headers.set("X-Dev-User", STRANGER.toString());
        });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefusedAsAProblemDocument() {
        ResponseEntity<JsonNode> response = register(headers -> headers.setBearerAuth(token(otherKey, USER, c -> {})));

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
                token(key, USER, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(600))))));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getBody().get("code").asText()).isEqualTo("token.invalid");

        ResponseEntity<JsonNode> elsewhere =
                register(headers -> headers.setBearerAuth(token(key, USER, c -> c.issuer("http://elsewhere.test"))));
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(elsewhere.getBody().get("code").asText()).isEqualTo("token.invalid");
    }

    @Test
    void aTokenThatIsNotAJwtIsRefusedTheSameWay() {
        ResponseEntity<JsonNode> response = register(headers -> headers.setBearerAuth("not.a.token"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("token.invalid");
    }

    @Test
    void withoutATokenTheDevelopmentHeadersStillNameTheUserUntilTheSecondPullRequest() {
        ResponseEntity<JsonNode> allowed = register(headers -> headers.set("X-Dev-User", USER.toString()));
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // No token and no user header: nobody to run the command as.
        ResponseEntity<JsonNode> nobody = register(headers -> {});
        assertThat(nobody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nobody.getBody().get("code").asText()).isEqualTo("scope.required");
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

    private static String token(RSAKey signingKey, UUID user, Consumer<JWTClaimsSet.Builder> customise) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(user.toString())
                    .issuer(ISSUER)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("ent", ENTITY.toString())
                    .claim("cls", "OWN")
                    .claim("lang", "en");
            customise.accept(claims);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
