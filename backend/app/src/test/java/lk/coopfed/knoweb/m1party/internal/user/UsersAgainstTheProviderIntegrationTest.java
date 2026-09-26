package lk.coopfed.knoweb.m1party.internal.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.UserActivated;
import lk.coopfed.knoweb.m1party.api.UserCreated;
import lk.coopfed.knoweb.m1party.api.UserCredentialReset;
import lk.coopfed.knoweb.m1party.api.UserDeactivated;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * M1-07's done criterion: create, reset and deactivate against the development realm. The
 * provider of the local stack runs here in a container with the same realm file
 * (infra/compose/realm-dev.json), and every step goes through the HTTP API as an administrator
 * of the entity would, with a bearer token. After each step the test asks the provider, as the
 * realm administrator and as the user signing in, what it now holds.
 */
class UsersAgainstTheProviderIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a707-0000-7000-8000-000000000071");
    private static final UUID ADMIN = UUID.fromString("0190a707-0000-7000-8000-0000000000a7");
    private static final String REALM_FILE = "../../infra/compose/realm-dev.json";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.4")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/realm-dev.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(4)));

    @DynamicPropertySource
    static void theProviderOfTheStack(DynamicPropertyRegistry registry) {
        KEYCLOAK.start();
        registry.add("coop-erp.security.oidc.admin.base-url", UsersAgainstTheProviderIntegrationTest::providerUrl);
    }

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    @AfterEach
    void clean() {
        superuserJdbc().update("delete from security.app_user where home_entity_id = ?", ENTITY);
    }

    @Test
    void createResetAndDeactivateAgainstTheDevelopmentRealm() {
        String username = "m107-" + UUID.randomUUID().toString().substring(0, 8);

        // Create: the platform's user, PENDING, and a login at the provider carrying its id.
        ResponseEntity<JsonNode> created = post(
                "/v1/security/users",
                Map.of("username", username, "displayName", "Nimal Silva", "language", "si", "userKind", "BACK_OFFICE"),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = UUID.fromString(created.getBody().path("userId").asText());
        assertThat(created.getBody().path("status").asText()).isEqualTo("PENDING");

        String subject = superuserJdbc()
                .queryForObject(
                        "select provider_subject from security.app_user where user_id = ?", String.class, userId);
        JsonNode login = userAtTheProvider(subject);
        assertThat(login.path("username").asText()).isEqualTo(username);
        assertThat(login.path("enabled").asBoolean()).isTrue();
        assertThat(login.path("attributes").path("uid").get(0).asText()).isEqualTo(userId.toString());
        assertThat(login.path("attributes").path("locale").get(0).asText()).isEqualTo("si");

        // Reset: a one-time password, answered once, which the provider accepts and asks to change.
        ResponseEntity<JsonNode> reset = post(
                "/v1/security/users/" + userId + "/reset-credential", Map.of("credential", "PASSWORD"), JsonNode.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().path("delivery").asText()).isEqualTo("RETURNED");
        assertThat(reset.getBody().path("status").asText()).isEqualTo("ACTIVE");
        String password = reset.getBody().path("temporaryPassword").asText();
        assertThat(password).hasSize(12);
        assertThat(userAtTheProvider(subject).path("requiredActions").toString())
                .contains("UPDATE_PASSWORD");
        // The right password with a required action left: the provider says the account is not
        // set up; a wrong one would be "Invalid user credentials".
        assertThat(signIn(username, password)).contains("not fully set up");
        assertThat(signIn(username, "not-the-password")).contains("Invalid user credentials");

        // Deactivate: the login is disabled and cannot sign in, the platform user is DEACTIVATED.
        ResponseEntity<Void> deactivated = post(
                "/v1/security/users/" + userId + "/deactivate", Map.of("reasonCode", "LEFT_EMPLOYMENT"), Void.class);
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userAtTheProvider(subject).path("enabled").asBoolean()).isFalse();
        assertThat(signIn(username, password)).contains("disabled");
        assertThat(superuserJdbc()
                        .queryForObject("select status from security.app_user where user_id = ?", String.class, userId))
                .isEqualTo("DEACTIVATED");

        // Each step committed its audit record and its event, in the caller's scope.
        assertThat(kernel.committedAudit())
                .extracting(AuditRecord::eventType)
                .containsExactly("USER_CREATED", "USER_CREDENTIAL_RESET", "USER_ACTIVATED", "USER_DEACTIVATED");
        assertThat(kernel.committedAudit())
                .allSatisfy(record -> assertThat(record.scope().entityId()).isEqualTo(ENTITY));
        assertThat(kernel.committedAudit().toString()).doesNotContain(password);
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new UserCreated(userId, userId, ENTITY, "BACK_OFFICE", "PENDING"),
                        new UserCredentialReset(userId, userId, ENTITY, "BACK_OFFICE", "ACTIVE", "PASSWORD"),
                        new UserActivated(userId, userId, ENTITY, "BACK_OFFICE", "ACTIVE"),
                        new UserDeactivated(userId, userId, ENTITY, "BACK_OFFICE", "DEACTIVATED"));
    }

    // ---- the platform's API, as the entity's administrator ----

    private <T> ResponseEntity<T> post(String url, Map<String, Object> body, Class<T> type) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    // ---- the provider, as its administrator and as the user ----

    private static String providerUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private static JsonNode userAtTheProvider(String subject) {
        RestClient rest = RestClient.create(providerUrl());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", "admin");
        form.add("password", "admin");
        JsonNode token = rest.post()
                .uri("/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        return rest.get()
                .uri("/admin/realms/coop/users/{id}", subject)
                .headers(h -> h.setBearerAuth(token.path("access_token").asText()))
                .retrieve()
                .body(JsonNode.class);
    }

    /** The provider's answer to a sign-in with the web client's password grant: its error, or "signed in". */
    private static String signIn(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "coop-erp-web");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");
        try {
            RestClient.create(providerUrl())
                    .post()
                    .uri("/realms/coop/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return "signed in";
        } catch (HttpClientErrorException e) {
            return e.getResponseBodyAsString();
        }
    }
}
