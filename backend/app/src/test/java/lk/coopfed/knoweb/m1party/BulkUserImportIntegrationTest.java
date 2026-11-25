package lk.coopfed.knoweb.m1party;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Proves CAT-09: A user sheet creates accounts that can sign in.
 *
 * <p>A user sheet is imported in bulk with identity-provider provisioning. Each account is
 * created in Keycloak with credentials set, and verified to authenticate successfully via
 * the token endpoint.
 */
class BulkUserImportIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a708-0000-7000-8000-000000000072");
    private static final UUID ADMIN = UUID.fromString("0190a708-0000-7000-8000-0000000000a8");

    @DynamicPropertySource
    static void theProviderOfTheStack(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.security.oidc.admin.base-url", BulkUserImportIntegrationTest::providerUrl);
    }

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void seedEntityAndCleanUsers() {
        clean();
        superuserJdbc()
                .update(
                        "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?) "
                                + "on conflict (entity_id) do update set entity_code = excluded.entity_code",
                        ENTITY,
                        "E-TEST-09",
                        "MPCS",
                        "CAT-09 Test Society");
    }

    @AfterEach
    void clean() {
        superuserJdbc()
                .update(
                        "delete from security.user_role where user_id in (select user_id from security.app_user where home_entity_id = ?)",
                        ENTITY);
        superuserJdbc().update("delete from security.app_user where home_entity_id = ?", ENTITY);
    }

    @Test
    void aUserSheetCreatesAccountsThatCanSignIn() {
        String mark = UUID.randomUUID().toString().substring(0, 6);
        String user1 = "u" + mark + "_mgr";
        String user2 = "u" + mark + "_lead";
        String user3 = "u" + mark + "_till";
        String pass1 = "MgrSecret123!";
        String pass2 = "LeadSecret456!";

        String sheet = String.format(
                """
                username,display_name,language,user_kind,pin,password
                %s,Branch Manager,en,BACK_OFFICE,,%s
                %s,Lead Operator,si,BOTH,1234,%s
                %s,Cashier Till,ta,TILL,5678,
                """,
                user1, pass1, user2, pass2, user3);

        // 1. Bulk import the user sheet
        ResponseEntity<JsonNode> res = post("/v1/security/users/bulk", Map.of("sheet", sheet), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().path("status").asText()).isEqualTo("IMPORTED");
        assertThat(res.getBody().path("imported").asInt()).isEqualTo(3);
        assertThat(res.getBody().path("failed").asInt()).isZero();

        // 2. Verify platform database records
        Map<String, Object> row1 = superuserJdbc()
                .queryForMap(
                        "select user_id, status, provider_subject, user_kind from security.app_user where username = ?",
                        user1);
        assertThat(row1.get("status")).isEqualTo("ACTIVE");
        assertThat(row1.get("provider_subject")).isNotNull();
        assertThat(row1.get("user_kind")).isEqualTo("BACK_OFFICE");

        Map<String, Object> row2 = superuserJdbc()
                .queryForMap(
                        "select user_id, status, provider_subject, user_kind, pin_hash from security.app_user where username = ?",
                        user2);
        assertThat(row2.get("status")).isEqualTo("ACTIVE");
        assertThat(row2.get("provider_subject")).isNotNull();
        assertThat(row2.get("user_kind")).isEqualTo("BOTH");
        assertThat(row2.get("pin_hash")).isNotNull();

        Map<String, Object> row3 = superuserJdbc()
                .queryForMap(
                        "select user_id, status, provider_subject, user_kind, pin_hash from security.app_user where username = ?",
                        user3);
        assertThat(row3.get("status")).isEqualTo("ACTIVE");
        assertThat(row3.get("provider_subject")).isNotNull();
        assertThat(row3.get("user_kind")).isEqualTo("TILL");
        assertThat(row3.get("pin_hash")).isNotNull();

        // 3. Verify Authentication Readiness against Keycloak
        // Valid passwords authenticate and return "signed in"
        assertThat(signIn(user1, pass1)).isEqualTo("signed in");
        assertThat(signIn(user2, pass2)).isEqualTo("signed in");

        // Invalid password is rejected by Keycloak
        assertThat(signIn(user1, "WrongPassword!")).contains("Invalid user credentials");

        // 4. All-or-none rejection: duplicate username in sheet commits nothing
        String dupSheet = String.format(
                """
                username,display_name,language,user_kind,pin,password
                %s_new,New User,en,BACK_OFFICE,,Secret!
                %s,Already Taken,en,BACK_OFFICE,,Secret!
                """,
                mark, user1);

        ResponseEntity<JsonNode> rejectRes = post("/v1/security/users/bulk", Map.of("sheet", dupSheet), JsonNode.class);

        assertThat(rejectRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejectRes.getBody().path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectRes.getBody().path("problems").toString()).contains("bulk.user.username_duplicate");

        // Ensure user was not committed
        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from security.app_user where username = ?", Integer.class, mark + "_new");
        assertThat(count).isZero();
    }

    private <T> ResponseEntity<T> post(String url, Map<String, Object> body, Class<T> type) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private static String providerUrl() {
        return lk.coopfed.knoweb.testsupport.SharedKeycloak.baseUrl();
    }

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
