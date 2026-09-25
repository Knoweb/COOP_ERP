package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.RoleAssigned;
import lk.coopfed.knoweb.m1party.api.RoleChanged;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Doc 21 flow 6.3, "Building a role for a shop", end to end through the API with signed tokens
 * (the done-when of M1-08): the Federation authors the shop-in-charge template; the MPCS
 * administrator clones it and adds a duty; the guardrails refuse a permission the administrator
 * does not hold, a pair in ROLE mode, and an assignment outside the administrator's scope; the
 * role is assigned at the shop and the kernel's resolver gives the permissions there and nowhere
 * else. Then doc 19 DR-4: the Federation changes the template, the clone shows "template
 * updated" with the diff, and the administrator takes the change.
 */
class BuildingARoleForAShopScenarioIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    PermissionResolver resolver;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    SecurityFixture fx;
    UUID federation;
    UUID mpcs;
    UUID otherMpcs;
    UUID shop;
    UUID secondShop;
    UUID otherShop;
    UUID federationAdmin;
    UUID mpcsAdmin;
    UUID shopKeeper;
    UUID stranger;

    @BeforeEach
    void arrange() {
        fx = new SecurityFixture(superuserJdbc());
        fx.clean();
        federation = fx.federation();
        mpcs = fx.mpcs("Kandy MPCS (M1-08 scenario)");
        otherMpcs = fx.mpcs("Galle MPCS (M1-08 scenario)");
        shop = fx.shop(mpcs);
        secondShop = fx.shop(mpcs);
        otherShop = fx.shop(otherMpcs);

        federationAdmin = fx.user(federation);
        fx.assign(
                federationAdmin,
                fx.role(
                        federation,
                        "gov.role.manage",
                        "gov.user.manage",
                        "prt.location.view",
                        "prt.location.manage",
                        "sys.device.view"),
                federation,
                null);

        mpcsAdmin = fx.user(mpcs);
        fx.assign(
                mpcsAdmin,
                fx.role(
                        mpcs,
                        "gov.role.manage",
                        "gov.user.manage",
                        "prt.location.view",
                        "prt.location.manage",
                        "sys.device.view",
                        "prt.position.manage",
                        "sys.device.manage"),
                mpcs,
                null);
        // The society's own rule: whoever registers till positions does not also enrol devices.
        fx.pair(mpcs, "prt.position.manage", "sys.device.manage", "ROLE");

        shopKeeper = fx.user(mpcs);
        stranger = fx.user(otherMpcs);
    }

    @AfterEach
    void cleanUp() {
        fx.clean();
    }

    @Test
    void anMpcsAdministratorBuildsARoleFromTheTemplateAndAssignsItAtTheShop() {
        // 1. The Federation authors the template.
        JsonNode template = call(
                        HttpMethod.POST,
                        "/v1/security/roles",
                        Map.of(
                                "nameEn",
                                SecurityFixture.NAME_PREFIX + "Shop In-Charge",
                                "asTemplate",
                                true,
                                "permissions",
                                items("prt.location.view", "sys.device.view")),
                        federationAdmin,
                        federation,
                        HttpStatus.CREATED)
                .getBody();
        UUID templateId = UUID.fromString(template.get("roleId").asText());
        assertThat(template.get("template").asBoolean()).isTrue();
        assertThat(template.get("version").asInt()).isEqualTo(1);

        // 2. The MPCS administrator sees it among the roles it may clone.
        JsonNode roles = call(HttpMethod.GET, "/v1/security/roles", null, mpcsAdmin, mpcs, HttpStatus.OK)
                .getBody();
        assertThat(roles.get("items").findValuesAsText("roleId")).contains(templateId.toString());

        // 3. The guardrails: a permission the administrator does not hold, and a ROLE-mode pair.
        assertRefused(
                clone(templateId, "prt.location.view", "sys.device.view", "prt.relationship.manage"),
                "m1.role.permission_not_held");
        assertRefused(
                clone(templateId, "prt.location.view", "sys.device.view", "prt.position.manage", "sys.device.manage"),
                "m1.role.sod_conflict");

        // 4. The clone, with one duty added, remembers the template version it was made from.
        JsonNode role = call(
                        HttpMethod.POST,
                        "/v1/security/roles",
                        clone(templateId, "prt.location.view", "sys.device.view", "prt.position.manage"),
                        mpcsAdmin,
                        mpcs,
                        HttpStatus.CREATED)
                .getBody();
        UUID roleId = UUID.fromString(role.get("roleId").asText());
        assertThat(role.get("templateRoleId").asText()).isEqualTo(templateId.toString());
        assertThat(role.get("templateVersionSeen").asInt()).isEqualTo(1);
        assertThat(role.get("templateUpdated").asBoolean()).isFalse();
        assertThat(role.get("permissions").findValuesAsText("permissionCode"))
                .containsExactly("prt.location.view", "prt.position.manage", "sys.device.view");

        // 5. Assignment outside the administrator's scope is refused; at the shop it is made.
        assertRefused(
                HttpMethod.POST,
                "/v1/security/assignments",
                Map.of("userId", shopKeeper, "roleId", roleId, "scopeLocationId", otherShop),
                "m1.assignment.location_not_in_entity");
        assertRefused(
                HttpMethod.POST,
                "/v1/security/assignments",
                Map.of("userId", stranger, "roleId", roleId, "scopeLocationId", shop),
                "m1.assignment.user_not_in_scope");
        call(
                HttpMethod.POST,
                "/v1/security/assignments",
                Map.of("userId", shopKeeper, "roleId", roleId, "scopeLocationId", shop),
                mpcsAdmin,
                mpcs,
                HttpStatus.NO_CONTENT);

        // 6. The kernel's resolver gives the duties at that shop and nowhere else.
        ScopeContext atShop = ScopeContext.dev(shopKeeper, mpcs, shop);
        ScopeContext atSecondShop = ScopeContext.dev(shopKeeper, mpcs, secondShop);
        assertThat(inScope(atShop, () -> resolver.resolve(atShop)))
                .containsExactlyInAnyOrder("prt.location.view", "prt.position.manage", "sys.device.view");
        assertThat(inScope(atSecondShop, () -> resolver.resolve(atSecondShop))).isEmpty();

        // 7. The Federation changes the template; nothing is pushed into the clone.
        call(
                HttpMethod.PUT,
                "/v1/security/roles/" + templateId + "/permissions",
                Map.of("permissions", items("prt.location.view", "prt.location.manage", "sys.device.view")),
                federationAdmin,
                federation,
                HttpStatus.OK);
        JsonNode drifted = call(HttpMethod.GET, "/v1/security/roles/" + roleId, null, mpcsAdmin, mpcs, HttpStatus.OK)
                .getBody();
        assertThat(drifted.get("templateUpdated").asBoolean())
                .as("the drift badge")
                .isTrue();
        assertThat(drifted.get("permissions").findValuesAsText("permissionCode"))
                .containsExactly("prt.location.view", "prt.position.manage", "sys.device.view");

        JsonNode diff = call(
                        HttpMethod.GET, "/v1/security/roles/" + roleId + "/diff", null, mpcsAdmin, mpcs, HttpStatus.OK)
                .getBody();
        assertThat(diff.get("templateVersionSeen").asInt()).isEqualTo(1);
        assertThat(diff.get("templateVersion").asInt()).isEqualTo(2);
        assertThat(diff.get("templateUpdated").asBoolean()).isTrue();
        assertThat(texts(diff.get("onlyInTemplate"))).containsExactly("prt.location.manage");
        assertThat(texts(diff.get("onlyInRole"))).containsExactly("prt.position.manage");

        // 8. The administrator takes the template's change, keeping the duty the society added.
        JsonNode adopted = call(
                        HttpMethod.PUT,
                        "/v1/security/roles/" + roleId + "/permissions",
                        Map.of(
                                "permissions",
                                items(
                                        "prt.location.view",
                                        "prt.location.manage",
                                        "sys.device.view",
                                        "prt.position.manage"),
                                "adoptTemplateVersion",
                                true),
                        mpcsAdmin,
                        mpcs,
                        HttpStatus.OK)
                .getBody();
        assertThat(adopted.get("templateUpdated").asBoolean()).isFalse();
        assertThat(adopted.get("templateVersionSeen").asInt()).isEqualTo(2);
        assertThat(adopted.get("version").asInt()).isEqualTo(2);
        JsonNode after = call(
                        HttpMethod.GET, "/v1/security/roles/" + roleId + "/diff", null, mpcsAdmin, mpcs, HttpStatus.OK)
                .getBody();
        assertThat(texts(after.get("onlyInTemplate"))).isEmpty();
        assertThat(texts(after.get("onlyInRole"))).containsExactly("prt.position.manage");

        // What was committed: five changes, each announced once; the refusals left no trace.
        List<DomainEvent> events = kernel.committedEvents();
        assertThat(events)
                .hasSize(5)
                .satisfiesExactly(
                        e -> assertThat(((RoleChanged) e).change()).isEqualTo(RoleChanged.CREATED),
                        e -> assertThat(((RoleChanged) e).roleId()).isEqualTo(roleId),
                        e -> assertThat(e).isEqualTo(new RoleAssigned(roleId, shopKeeper, mpcs, shop)),
                        e -> assertThat(((RoleChanged) e).permissionsAdded()).containsExactly("prt.location.manage"),
                        e -> assertThat(((RoleChanged) e).permissionsAdded()).containsExactly("prt.location.manage"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("ROLE_CHANGED", "ROLE_CHANGED", "ROLE_ASSIGNED", "ROLE_CHANGED", "ROLE_CHANGED");
    }

    private Map<String, Object> clone(UUID templateId, String... permissions) {
        return Map.of(
                "nameEn",
                SecurityFixture.NAME_PREFIX + "Shop In-Charge (Kandy)",
                "templateRoleId",
                templateId,
                "permissions",
                items(permissions));
    }

    private static List<Map<String, Object>> items(String... codes) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String code : codes) {
            items.add(Map.of("permissionCode", code));
        }
        return items;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private void assertRefused(Map<String, Object> createRoleBody, String code) {
        assertRefused(HttpMethod.POST, "/v1/security/roles", createRoleBody, code);
    }

    private void assertRefused(HttpMethod method, String url, Map<String, Object> body, String code) {
        ResponseEntity<JsonNode> response = call(method, url, body, mpcsAdmin, mpcs, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private ResponseEntity<JsonNode> call(
            HttpMethod method, String url, Object body, UUID user, UUID entity, HttpStatus expected) {
        HttpHeaders headers = TestIdentityProvider.headers(user, entity);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (method != HttpMethod.GET) {
            headers.set("Idempotency-Key", Ids.next().toString());
        }
        ResponseEntity<JsonNode> response = http.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode())
                .as(method + " " + url + ": " + response.getBody())
                .isEqualTo(expected);
        return response;
    }

    private <T> T inScope(ScopeContext scope, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    scope.userId().toString(),
                    Ids.next().toString(),
                    scope.entityId().toString(),
                    scope.locationId() == null ? "" : scope.locationId().toString());
            return work.get();
        });
    }
}
