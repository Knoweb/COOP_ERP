package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DeviceCredentials;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * {@link DeviceCredentials} against the provider the local stack runs, started with the same
 * realm file (infra/compose/realm-dev.json): the credential a device receives at enrolment is a
 * client of the provider whose client-credentials tokens carry {@code dev} and
 * {@code cls = DEVICE} (doc 19 section 2.1; 19A section 2), a second enrolment gives it a new
 * secret and the old one stops working, and a revoked device obtains no token at all.
 */
class KeycloakDeviceCredentialsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a700-0000-7000-8000-000000000001");
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
        registry.add("coop-erp.security.oidc.admin.base-url", KeycloakDeviceCredentialsIntegrationTest::base);
        registry.add(
                "coop-erp.security.oidc.token-endpoint", () -> base() + "/realms/coop/protocol/openid-connect/token");
    }

    @Autowired
    DeviceCredentials credentials;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aDevicesCredentialGivesDeviceTokensUntilItIsRotatedOrRevoked() throws Exception {
        UUID device = UUID.randomUUID();

        DeviceCredentials.Credential first = credentials.issue(device, ENTITY);

        assertThat(first.clientId()).isEqualTo("device-" + device);
        assertThat(first.toString()).doesNotContain(first.clientSecret());
        JsonNode claims = claimsOf(token(first.tokenEndpoint(), first.clientId(), first.clientSecret()));
        assertThat(claims.path("dev").asText()).isEqualTo(device.toString());
        assertThat(claims.path("cls").asText()).isEqualTo("DEVICE");
        // The client asks for 24 hours (doc 19 section 2.1); the provider caps an access token at
        // the realm's session maximum, ten hours in the dev realm. Either way hours, not minutes:
        // a till refreshes on sync, it does not sign in every quarter of an hour like a user.
        long lifetime = claims.path("exp").asLong() - claims.path("iat").asLong();
        assertThat(lifetime).isBetween(35_000L, 86_400L);

        // Enrolling again: a new secret, the old one refused.
        DeviceCredentials.Credential second = credentials.issue(device, ENTITY);
        assertThat(second.clientSecret()).isNotEqualTo(first.clientSecret());
        assertThat(claimsOf(token(second.tokenEndpoint(), second.clientId(), second.clientSecret()))
                        .path("dev")
                        .asText())
                .isEqualTo(device.toString());
        assertThat(tokenStatus(first.tokenEndpoint(), first.clientId(), first.clientSecret()))
                .isEqualTo(401);

        credentials.revoke(device);
        assertThat(tokenStatus(second.tokenEndpoint(), second.clientId(), second.clientSecret()))
                .isIn(400, 401);
    }

    private static String base() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private static String token(String endpoint, String clientId, String secret) {
        return RestClient.create()
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form(clientId, secret))
                .retrieve()
                .body(JsonNode.class)
                .path("access_token")
                .asText();
    }

    private static int tokenStatus(String endpoint, String clientId, String secret) {
        try {
            token(endpoint, clientId, secret);
            return 200;
        } catch (RestClientResponseException refused) {
            return refused.getStatusCode().value();
        }
    }

    private static MultiValueMap<String, String> form(String clientId, String secret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        return form;
    }

    private JsonNode claimsOf(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return json.readTree(Base64.getUrlDecoder().decode(payload));
    }
}
