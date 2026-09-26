package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
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
 * The interceptor with enforcement on (19A section 3, "interceptor order"): a command from a
 * user whose roles lack the handler's permission is refused with 403 and the problem document
 * {@code permission.denied}, before the idempotency claim; the same user with the permission
 * granted runs the command. Everything else in the build runs with enforcement off until
 * K-02, so this test switches it on for its own context.
 */
class PermissionEnforcementPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a400-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190a400-0000-7000-8000-000000000010");
    private static final UUID ROLE = UUID.fromString("0190a400-0000-7000-8000-000000000201");

    @DynamicPropertySource
    static void enforce(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.security.enforce-permissions", () -> "true");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcPermissionResolver resolver;

    /** True when this test inserted the hello permission, so that it is the one to remove it. */
    private boolean insertedHelloPermission;

    @BeforeEach
    void aUserWithARoleButNoPermissionYet() {
        JdbcTemplate admin = superuserJdbc();
        removeTheUserAndTheRole(admin);
        // The hello permission is the template module's; the catalogue of M1 does not seed it.
        insertedHelloPermission = admin.update(
                        "insert into security.permission (permission_code, module, description_en, offline_allowed, requires_mfa, scope)"
                                + " values ('hello.greeting.register', 'hello', 'Register a greeting', false, false, 'ENTITY')"
                                + " on conflict (permission_code) do nothing")
                == 1;
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, 'Enforcement test user', 'BACK_OFFICE', 'ACTIVE')",
                USER,
                ENTITY,
                "u-" + USER);
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, 'Greeter', false, 'OWN', 'ACTIVE')",
                ROLE,
                ENTITY);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, null)",
                USER,
                ROLE,
                ENTITY);
        resolver.invalidateAll();
    }

    /**
     * The database is shared by every integration test of the JVM: what this test adds to the
     * permission catalogue it takes away again, or a later test that counts the catalogue
     * (M1SeedLoaderTest) fails far from the cause (review of 26 September 2026).
     */
    @AfterEach
    void leaveTheCatalogueAsItWas() {
        JdbcTemplate admin = superuserJdbc();
        removeTheUserAndTheRole(admin);
        if (insertedHelloPermission) {
            admin.execute("delete from security.permission where permission_code = 'hello.greeting.register'");
        }
        resolver.invalidateAll();
    }

    private static void removeTheUserAndTheRole(JdbcTemplate admin) {
        admin.execute("delete from security.user_role where user_id = '" + USER + "'");
        admin.execute("delete from security.role_permission where role_id = '" + ROLE + "'");
        admin.execute("delete from security.role where role_id = '" + ROLE + "'");
        admin.execute("delete from security.app_user where user_id = '" + USER + "'");
    }

    @Test
    void aCommandIsRefusedWithoutThePermissionAndRunsWithIt() {
        ResponseEntity<JsonNode> refused = register("Refused " + UUID.randomUUID());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("permission.denied");
        assertThat(refused.getBody().get("title").asText()).isEqualTo("Your role does not allow this action");

        superuserJdbc()
                .update(
                        "insert into security.role_permission (role_id, permission_code) values (?, 'hello.greeting.register')",
                        ROLE);
        resolver.invalidateAll();

        ResponseEntity<JsonNode> allowed = register("Allowed " + UUID.randomUUID());
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<JsonNode> register(String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(USER, ENTITY));
        headers.set("X-Scope-Entity", ENTITY.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/v1/hello/greetings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("textEn", text), headers),
                JsonNode.class);
    }
}
