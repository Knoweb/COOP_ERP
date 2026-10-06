package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * The kernel enforces the x-permission of every GET (CR-19A-9, decided 27 September 2026), with
 * enforcement on, over HTTP, for every class of caller:
 *
 * <ul>
 *   <li>OWN: a read is refused without the code in the caller's roles and served with it;
 *   <li>FEDERATION_VIEW: every read, nothing else;
 *   <li>EXTERNAL_TIMEBOXED: doc 21 flow 6.5 end to end with an EXTERNAL token. The Federation
 *       admin grants the regulator a time-boxed view of society A and assigns it the Regulator
 *       role; the regulator reads society A's shops and nothing of society B, may name society
 *       A as its active scope and no other, is refused a read its role does not carry and every
 *       command; after the revocation it reads nothing at all;
 *   <li>everybody: {@code GET /v1/session} answers the caller's own facts and the permissions
 *       resolved for the active scope.
 * </ul>
 *
 * <p>Rows are written as the superuser where no command exists for them in a kernel test (the
 * entities, the shops, the users, the roles); the grant, the assignment and the revocation go
 * through M1's commands over HTTP, as the flow does.
 */
class ReadPermissionsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String LOCATIONS = "/v1/party/locations";
    private static final String USERS = "/v1/security/users";
    private static final String GRANTS = "/v1/security/external-grants";
    private static final String ASSIGNMENTS = "/v1/security/assignments";
    private static final String SESSION = "/v1/session";

    private static final UUID FED_ADMIN = UUID.fromString("0190a900-0000-7000-8000-000000000001");
    private static final UUID FED_VIEWER = UUID.fromString("0190a900-0000-7000-8000-000000000002");
    private static final UUID REGULATOR = UUID.fromString("0190a900-0000-7000-8000-000000000003");
    private static final UUID CLERK = UUID.fromString("0190a900-0000-7000-8000-000000000004");

    private static final UUID FED_ADMIN_ROLE = UUID.fromString("0190a900-0000-7000-8000-000000000101");
    private static final UUID REGULATOR_TEMPLATE = UUID.fromString("0190a900-0000-7000-8000-000000000102");
    private static final UUID CLERK_ROLE = UUID.fromString("0190a900-0000-7000-8000-000000000103");

    private static final List<UUID> ALL_USERS = List.of(FED_ADMIN, FED_VIEWER, REGULATOR, CLERK);
    private static final List<UUID> ALL_ROLES = List.of(FED_ADMIN_ROLE, REGULATOR_TEMPLATE, CLERK_ROLE);

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

    private UUID federation;
    private boolean madeFederation;
    private UUID societyA;
    private UUID societyB;
    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void theFederationTwoSocietiesTheirShopsAndFourPeople() {
        JdbcTemplate admin = superuserJdbc();
        clean(admin);

        List<UUID> found =
                admin.queryForList("select entity_id from party.entity where entity_type = 'FEDERATION'", UUID.class);
        if (found.isEmpty()) {
            federation = UUID.randomUUID();
            madeFederation = true;
            insertEntity(admin, federation, "FEDERATION", "Federation (read permissions test)");
        } else {
            federation = found.get(0);
        }
        // The policies that ask kernel.system_entity() (m1party V0012, m1security V0016) must know it.
        theDatabaseNamesTheFederation(federation);
        societyA = UUID.randomUUID();
        societyB = UUID.randomUUID();
        insertEntity(admin, societyA, "MPCS", "Society A (read permissions test)");
        insertEntity(admin, societyB, "MPCS", "Society B (read permissions test)");
        shopA = insertShop(admin, societyA);
        shopB = insertShop(admin, societyB);

        insertUser(admin, FED_ADMIN, federation, "BACK_OFFICE");
        insertUser(admin, FED_VIEWER, federation, "BACK_OFFICE");
        insertUser(admin, REGULATOR, federation, "EXTERNAL");
        insertUser(admin, CLERK, societyA, "BACK_OFFICE");

        // The Federation admin: grants, assigns, and holds what the regulator's role holds (assigning is granting).
        insertRole(admin, FED_ADMIN_ROLE, federation, false, "OWN");
        for (String code : List.of("gov.external.grant", "gov.role.manage", "gov.entity.view", "prt.location.view")) {
            admin.update(
                    "insert into security.role_permission (role_id, permission_code) values (?, ?)",
                    FED_ADMIN_ROLE,
                    code);
        }
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, null)",
                FED_ADMIN,
                FED_ADMIN_ROLE,
                federation);
        // The Regulator template (seed/m1party/role-templates.yaml), written here so the test owns its rows.
        insertRole(admin, REGULATOR_TEMPLATE, null, true, "EXTERNAL_TIMEBOXED");
        for (String code : List.of("gov.entity.view", "prt.location.view")) {
            admin.update(
                    "insert into security.role_permission (role_id, permission_code) values (?, ?)",
                    REGULATOR_TEMPLATE,
                    code);
        }
        // A clerk of society A with a role that carries no read yet.
        insertRole(admin, CLERK_ROLE, societyA, false, "OWN");
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id) values (?, ?, ?, null)",
                CLERK,
                CLERK_ROLE,
                societyA);

        resolver.invalidateAll();
        userScopes.invalidateAll();
    }

    @AfterEach
    void leaveTheTablesAsTheyWere() {
        clean(superuserJdbc());
    }

    // ---- OWN and FEDERATION_VIEW -------------------------------------------------------------

    @Test
    void anOwnReadIsRefusedWithoutTheCodeAndServedWithIt() {
        ResponseEntity<JsonNode> refused = get(LOCATIONS, ownHeaders(CLERK, societyA));
        assertThat(refused.getStatusCode())
                .as(String.valueOf(refused.getBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("permission.denied");
        assertThat(refused.getBody().get("params").get("permission").asText()).isEqualTo("prt.location.view");

        superuserJdbc()
                .update(
                        "insert into security.role_permission (role_id, permission_code) values (?, 'prt.location.view')",
                        CLERK_ROLE);
        resolver.invalidateAll();

        ResponseEntity<JsonNode> served = get(LOCATIONS, ownHeaders(CLERK, societyA));
        assertThat(served.getStatusCode()).as(String.valueOf(served.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(owners(served)).containsExactly(societyA);

        ResponseEntity<JsonNode> session = get(SESSION, ownHeaders(CLERK, societyA));
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody().get("policyClass").asText()).isEqualTo("OWN");
        assertThat(session.getBody().get("activeScope").get("entityId").asText())
                .isEqualTo(societyA.toString());
        assertThat(texts(session.getBody().get("permissions"))).containsExactly("prt.location.view");
        assertThat(texts(session.getBody().get("grantedEntities"))).isEmpty();
    }

    @Test
    void theFederationViewReadsEverythingAndRunsNothing() {
        HttpHeaders viewer = headers(TestIdentityProvider.token(FED_VIEWER, federation, "FEDERATION_VIEW"), null);

        ResponseEntity<JsonNode> locations = get(LOCATIONS, viewer);
        assertThat(locations.getStatusCode())
                .as(String.valueOf(locations.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(owners(locations)).contains(societyA, societyB);

        ResponseEntity<JsonNode> users = get(USERS, viewer);
        assertThat(users.getStatusCode()).as(String.valueOf(users.getBody())).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> session = get(SESSION, viewer);
        assertThat(session.getBody().get("policyClass").asText()).isEqualTo("FEDERATION_VIEW");
        assertThat(texts(session.getBody().get("permissions")))
                .contains("prt.location.view", "gov.user.view", "cat.sku.view")
                .doesNotContain("prt.location.register", "gov.entity.register");

        // A command is refused by the class rule, although the read set carries the same code.
        ResponseEntity<JsonNode> command =
                post(GRANTS + "/" + UUID.randomUUID() + "/revoke", Map.of("reason", "x"), viewer);
        assertThat(command.getStatusCode())
                .as(String.valueOf(command.getBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(command.getBody().get("code").asText()).isEqualTo("permission.denied");
    }

    /**
     * Wave 2, TWK-19: Spring serves HEAD from a GET mapping by running the same handler, so a HEAD
     * is checked as the GET it is. One GET of every slice, sent as HEAD by a clerk whose role
     * carries no read: 403 each time, never the answer's status and size.
     */
    @Test
    void aHeadIsCheckedAsTheGetItIsOnOneReadOfEverySlice() throws Exception {
        java.util.Map<String, String> oneReadPerSlice = new java.util.TreeMap<>();
        org.springframework.core.io.Resource[] slices =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath*:openapi/*.yaml");
        for (org.springframework.core.io.Resource slice : slices) {
            if ("common.yaml".equals(slice.getFilename())) {
                continue;
            }
            try (java.io.InputStream in = slice.getInputStream()) {
                Map<String, Object> document = new org.yaml.snakeyaml.Yaml().load(in);
                String path = firstUserRead(document);
                if (path != null) {
                    oneReadPerSlice.put(slice.getFilename(), path);
                }
            }
        }
        assertThat(oneReadPerSlice)
                .as("a read of every slice that has one")
                .containsKeys("m1party.yaml", "m2catalogue.yaml", "m4trading.yaml", "m6pos.yaml");

        List<String> answered = new ArrayList<>();
        oneReadPerSlice.forEach((slice, template) -> {
            String path = template.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            ResponseEntity<Void> head =
                    http.exchange(path, HttpMethod.HEAD, new HttpEntity<>(ownHeaders(CLERK, societyA)), Void.class);
            if (head.getStatusCode() != HttpStatus.FORBIDDEN) {
                answered.add(slice + " HEAD " + path + ": " + head.getStatusCode());
            }
        });
        assertThat(answered)
                .as("HEAD requests answered without the read permission")
                .isEmpty();
    }

    /** The first GET of the slice that a user holds a permission for (not the device's, not the session). */
    @SuppressWarnings("unchecked")
    private static String firstUserRead(Map<String, Object> document) {
        Map<String, Object> paths = (Map<String, Object>) document.getOrDefault("paths", Map.of());
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) entry.getValue();
            Object get = item.get("get");
            if (!(get instanceof Map<?, ?> operation)) {
                continue;
            }
            Object permission = operation.get("x-permission");
            if (permission == null
                    || SliceOperations.AUTHENTICATED.equals(permission.toString())
                    || lk.coopfed.knoweb.kernel.internal.ScopeFilter.isDeviceOperation(entry.getKey())) {
                continue;
            }
            return entry.getKey();
        }
        return null;
    }

    // ---- EXTERNAL_TIMEBOXED: doc 21 flow 6.5 --------------------------------------------------

    @Test
    void scenario65TheRegulatorReadsItsGrantThroughItsRoleUntilTheGrantIsRevoked() {
        HttpHeaders fedAdmin = ownHeaders(FED_ADMIN, federation, withFreshSecondFactor());
        HttpHeaders regulator = headers(TestIdentityProvider.token(REGULATOR, federation, "EXTERNAL_TIMEBOXED"), null);

        // Before the grant: an EXTERNAL user with no grant and no role reads nothing.
        ResponseEntity<JsonNode> nothingYet = get(LOCATIONS, regulator);
        assertThat(nothingYet.getStatusCode())
                .as(String.valueOf(nothingYet.getBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nothingYet.getBody().get("code").asText()).isEqualTo("permission.denied");

        // Federation admin: GrantExternalView with scope and valid_until (M1-09).
        Instant until = Instant.now().plus(Duration.ofDays(30));
        ResponseEntity<JsonNode> issued = post(
                GRANTS,
                Map.of(
                        "granteeUserId", REGULATOR.toString(),
                        "scopeEntityIds", List.of(societyA.toString()),
                        "validUntil", until.toString(),
                        "reason", "Inspection"),
                fedAdmin);
        assertThat(issued.getStatusCode()).as(String.valueOf(issued.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID grantId = UUID.fromString(issued.getBody().get("grantId").asText());

        // A grant alone opens no read: the role says what the regulator may read.
        ResponseEntity<JsonNode> grantAlone = get(LOCATIONS, regulator);
        assertThat(grantAlone.getStatusCode())
                .as(String.valueOf(grantAlone.getBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Federation admin: AssignRole of the Regulator template to the EXTERNAL user.
        ResponseEntity<JsonNode> assigned = post(
                ASSIGNMENTS, Map.of("userId", REGULATOR.toString(), "roleId", REGULATOR_TEMPLATE.toString()), fedAdmin);
        assertThat(assigned.getStatusCode())
                .as(String.valueOf(assigned.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The regulator reads society A's shops, nothing of society B's.
        ResponseEntity<JsonNode> shops = get(LOCATIONS, regulator);
        assertThat(shops.getStatusCode()).as(String.valueOf(shops.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(owners(shops)).containsExactly(societyA);
        assertThat(texts(shops.getBody().get("items").findValues("locationId")))
                .contains(shopA.toString())
                .doesNotContain(shopB.toString());

        // It may name the inspected society as its active scope, and no other entity.
        HttpHeaders atSocietyA =
                headers(TestIdentityProvider.token(REGULATOR, federation, "EXTERNAL_TIMEBOXED"), societyA);
        assertThat(get(LOCATIONS, atSocietyA).getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders atSocietyB =
                headers(TestIdentityProvider.token(REGULATOR, federation, "EXTERNAL_TIMEBOXED"), societyB);
        ResponseEntity<JsonNode> elsewhere = get(LOCATIONS, atSocietyB);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(elsewhere.getBody().get("code").asText()).isEqualTo("scope.invalid");

        // A read its role does not carry is refused; so is every command.
        ResponseEntity<JsonNode> users = get(USERS, regulator);
        assertThat(users.getStatusCode()).as(String.valueOf(users.getBody())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.getBody().get("params").get("permission").asText()).isEqualTo("gov.user.view");
        ResponseEntity<JsonNode> command = post(GRANTS + "/" + grantId + "/revoke", Map.of("reason", "x"), regulator);
        assertThat(command.getStatusCode())
                .as(String.valueOf(command.getBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(command.getBody().get("code").asText()).isEqualTo("permission.denied");

        // What the web shell would show it.
        ResponseEntity<JsonNode> session = get(SESSION, regulator);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody().get("userId").asText()).isEqualTo(REGULATOR.toString());
        assertThat(session.getBody().get("policyClass").asText()).isEqualTo("EXTERNAL_TIMEBOXED");
        assertThat(texts(session.getBody().get("grantedEntities"))).containsExactly(societyA.toString());
        assertThat(texts(session.getBody().get("permissions"))).containsExactly("gov.entity.view", "prt.location.view");

        // Revocation is immediate: the role stays, the grant is gone, and with it every read.
        ResponseEntity<JsonNode> revoked =
                post(GRANTS + "/" + grantId + "/revoke", Map.of("reason", "Inspection closed"), fedAdmin);
        assertThat(revoked.getStatusCode())
                .as(String.valueOf(revoked.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> afterRevoke = get(LOCATIONS, regulator);
        assertThat(afterRevoke.getStatusCode())
                .as(String.valueOf(afterRevoke.getBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> sessionAfter = get(SESSION, regulator);
        assertThat(texts(sessionAfter.getBody().get("grantedEntities"))).isEmpty();
        assertThat(texts(sessionAfter.getBody().get("permissions"))).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String url, Object body, HttpHeaders headers) {
        HttpHeaders sent = new HttpHeaders();
        sent.putAll(headers);
        sent.set("Idempotency-Key", UUID.randomUUID().toString());
        sent.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, sent), JsonNode.class);
    }

    private static HttpHeaders ownHeaders(UUID user, UUID entity) {
        return ownHeaders(user, entity, claims -> {});
    }

    private static HttpHeaders ownHeaders(UUID user, UUID entity, Consumer<JWTClaimsSet.Builder> customise) {
        return headers(TestIdentityProvider.token(user, entity, "OWN", customise), entity);
    }

    private static HttpHeaders headers(String token, UUID activeEntity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (activeEntity != null) {
            headers.set("X-Scope-Entity", activeEntity.toString());
        }
        return headers;
    }

    /** The second factor presented now: the grant and the assignment ask for a fresh one. */
    private static Consumer<JWTClaimsSet.Builder> withFreshSecondFactor() {
        return claims -> claims.claim("mfa_at", Instant.now().getEpochSecond());
    }

    private static List<UUID> owners(ResponseEntity<JsonNode> page) {
        List<UUID> owners = new ArrayList<>();
        page.getBody()
                .get("items")
                .forEach(item ->
                        owners.add(UUID.fromString(item.get("ownerEntityId").asText())));
        return owners.stream().distinct().toList();
    }

    private static List<String> texts(Iterable<JsonNode> nodes) {
        List<String> texts = new ArrayList<>();
        nodes.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private static void insertEntity(JdbcTemplate admin, UUID id, String type, String name) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?)",
                id,
                "R" + id.toString().replace("-", "").substring(0, 10).toUpperCase(),
                type,
                name);
    }

    private static UUID insertShop(JdbcTemplate admin, UUID owner) {
        UUID id = UUID.randomUUID();
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                        + " values (?, ?, ?, 'SHOP', ?)",
                id,
                owner,
                "S" + id.toString().replace("-", "").substring(0, 10).toUpperCase(),
                "Shop " + id);
        return id;
    }

    private static void insertUser(JdbcTemplate admin, UUID id, UUID home, String kind) {
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, ?, ?, ?, 'ACTIVE')",
                id,
                home,
                "u-" + id,
                "User " + id,
                kind);
    }

    private static void insertRole(JdbcTemplate admin, UUID id, UUID owner, boolean template, String roleClass) {
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                        + " values (?, ?, ?, ?, ?, 'ACTIVE')",
                id,
                owner,
                "Read permissions test " + id,
                template,
                roleClass);
    }

    private void clean(JdbcTemplate admin) {
        String users = ALL_USERS.stream()
                .map(id -> "'" + id + "'")
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();
        String roles = ALL_ROLES.stream()
                .map(id -> "'" + id + "'")
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();
        admin.execute("delete from security.external_grant where grantee_user_id in (" + users + ")");
        admin.execute("delete from security.user_role where user_id in (" + users + ") or role_id in (" + roles + ")");
        admin.execute("delete from security.role_permission where role_id in (" + roles + ")");
        admin.execute("delete from security.role where role_id in (" + roles + ")");
        admin.execute("delete from security.app_user where user_id in (" + users + ")");
        admin.execute("delete from party.location where name_en like 'Shop %' and owner_entity_id in"
                + " (select entity_id from party.entity where legal_name_en like '%(read permissions test)')");
        // The directory row is kept by a trigger of m1party V0003 for every entity written.
        admin.execute("delete from party.entity_party_directory where entity_id in"
                + " (select entity_id from party.entity where legal_name_en like 'Society % (read permissions test)')");
        admin.execute("delete from party.entity where legal_name_en like 'Society % (read permissions test)'");
        if (madeFederation) {
            admin.execute("delete from party.entity_party_directory where entity_id = '" + federation + "'");
            admin.execute("delete from party.entity where entity_id = '" + federation + "'");
            madeFederation = false;
        }
    }
}
