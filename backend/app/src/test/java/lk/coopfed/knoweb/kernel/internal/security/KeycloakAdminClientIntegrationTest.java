package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * {@link IdentityProviderClient} against the provider the local stack runs, started here with
 * the same realm file (infra/compose/realm-dev.json), so what the client does to a user is
 * what an administrator sees in the development console. The scope rule of ADR-18 is proved
 * on the way: an administrator of another entity is refused before the provider is called.
 */
class KeycloakAdminClientIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a700-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190a700-0000-7000-8000-000000000002");
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
        registry.add(
                "coop-erp.security.oidc.admin.base-url",
                () -> "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080));
    }

    @Autowired
    IdentityProviderClient provider;

    private UUID userId;
    private String username;

    @BeforeEach
    void aFreshPlatformUser() {
        userId = UUID.randomUUID();
        username = "u-" + userId.toString().substring(0, 8);
    }

    @Test
    void theWholeLifeOfALoginAndTheScopeRuleAroundIt() {
        ScopeContext admin = SystemScope.own(ENTITY, null);
        ScopeContext elsewhere = SystemScope.own(OTHER_ENTITY, null);
        ScopeContext atAShop = SystemScope.own(ENTITY, UUID.fromString("0190a700-0000-7000-8000-000000000101"));

        // Creating outside the user's home entity is refused before the provider hears of it.
        assertThatThrownBy(() -> provider.createUser(elsewhere, userId, ENTITY, username, Locale.forLanguageTag("si")))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("identity.scope");

        String subject = provider.createUser(admin, userId, ENTITY, username, Locale.forLanguageTag("si"));
        assertThat(subject).isNotBlank();
        JsonNode created = userAtTheProvider(subject);
        assertThat(created.path("username").asText()).isEqualTo(username);
        assertThat(created.path("enabled").asBoolean()).isTrue();
        assertThat(created.path("attributes").path("uid").get(0).asText()).isEqualTo(userId.toString());
        assertThat(created.path("attributes").path("locale").get(0).asText()).isEqualTo("si");
        // What the realm's token mappers read: without these a new login's tokens carried no class.
        assertThat(created.path("attributes").path("ent").get(0).asText()).isEqualTo(ENTITY.toString());
        assertThat(created.path("attributes").path("cls").get(0).asText()).isEqualTo("OWN");

        // The same name twice is the provider's conflict, said in the platform's words.
        assertThatThrownBy(() -> provider.createUser(admin, UUID.randomUUID(), ENTITY, username, Locale.ENGLISH))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("identity.username_taken");

        // M1 stores the subject on the user; the rule for the other calls reads it from there.
        JdbcTemplate db = superuserJdbc();
        db.execute("delete from security.app_user where user_id = '" + userId + "'");
        db.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status, provider_subject)"
                        + " values (?, ?, ?, 'Provider test user', 'BACK_OFFICE', 'ACTIVE', ?)",
                userId,
                ENTITY,
                username,
                subject);

        assertThatThrownBy(() -> provider.setTemporaryPassword(elsewhere, subject))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("identity.scope");
        // A manager acting at one shop manages nobody's login: user management is entity-wide.
        assertThatThrownBy(() -> provider.setTemporaryPassword(atAShop, subject))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("identity.scope");

        IdentityProviderClient.TemporaryPassword password = provider.setTemporaryPassword(admin, subject);
        assertThat(password.value()).hasSize(12);
        assertThat(password.toString()).doesNotContain(password.value());
        assertThat(userAtTheProvider(subject).path("requiredActions").toString())
                .contains("UPDATE_PASSWORD");

        provider.resetTotp(admin, subject);
        String actions = userAtTheProvider(subject).path("requiredActions").toString();
        assertThat(actions).contains("CONFIGURE_TOTP");
        // The pending password change survives the second-factor reset.
        assertThat(actions).contains("UPDATE_PASSWORD");

        provider.revokeSessions(admin, subject);

        provider.disableUser(admin, subject);
        assertThat(userAtTheProvider(subject).path("enabled").asBoolean()).isFalse();

        db.execute("delete from security.app_user where user_id = '" + userId + "'");
    }

    // ---- what the administrator would see: the provider's own API, as the realm administrator ----

    private JsonNode userAtTheProvider(String subject) {
        String base = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        RestClient rest = RestClient.create(base);
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

    @SuppressWarnings("unused")
    private static Map<String, List<String>> attributes(String key, String value) {
        return Map.of(key, List.of(value));
    }
}
