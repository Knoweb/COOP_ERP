package lk.coopfed.knoweb.m1party.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.EntityUpdated;
import lk.coopfed.knoweb.m1party.query.UserQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(AppointResponsibleOfficerPostgresIntegrationTest.TestBeans.class)
class AppointResponsibleOfficerPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000009301");

    private static final UUID CALLER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000009302");

    private static final UUID OFFICER_ID = UUID.fromString("00000000-0000-0000-0000-000000009303");

    private static final LocalDate SIGNED_ON = LocalDate.of(2026, 9, 23);

    private static final String ENTITY_CODE = "M9301";

    private static final String URL = "/v1/party/entities/" + ENTITY_ID + "/responsible-officer";

    private static final UUID FEDERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000009304");

    private static final UUID OTHER_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000009305");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private UserQueries users;

    @MockBean
    private CurrentScope currentScope;

    @BeforeEach
    void seedEntity() {

        superuserJdbc().update("delete from security.app_user where user_id = ?", OFFICER_ID);
        // The entity is kept between tests (the party directory refers to it once an event has
        // been projected); only its officer is reset.
        superuserJdbc()
                .update(
                        "update party.entity set responsible_officer_user_id = null, data_governance_signed_on = null"
                                + " where entity_id = ?",
                        ENTITY_ID);

        superuserJdbc()
                .update(
                        """
                insert into party.entity (
                    entity_id,
                    entity_code,
                    entity_type,
                    legal_name_en
                )
                values (?, ?, 'MPCS', ?)
                on conflict (entity_id) do nothing
                """,
                        ENTITY_ID,
                        ENTITY_CODE,
                        "MPCS 9301");
    }

    @Test
    void appointingResponsibleOfficerCommitsEntityAuditAndEvent() {

        when(currentScope.get()).thenReturn(ScopeContext.dev(CALLER_USER_ID, ENTITY_ID, null));
        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_JSON);

        headers.setBearerAuth(TestIdentityProvider.token(CALLER_USER_ID, ENTITY_ID));
        headers.set("X-Scope-Entity", ENTITY_ID.toString());

        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> request = Map.of(
                "userId", OFFICER_ID.toString(),
                "dataGovernanceSignedOn", SIGNED_ON.toString());

        ResponseEntity<Void> response =
                http.exchange(URL, HttpMethod.PUT, new HttpEntity<>(request, headers), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        UUID savedOfficer = superuserJdbc()
                .queryForObject(
                        """
                        select responsible_officer_user_id
                        from party.entity
                        where entity_id = ?
                        """,
                        UUID.class,
                        ENTITY_ID);

        String savedSignedOn = superuserJdbc()
                .queryForObject(
                        """
                        select data_governance_signed_on::text
                        from party.entity
                        where entity_id = ?
                        """,
                        String.class,
                        ENTITY_ID);

        assertThat(savedOfficer).isEqualTo(OFFICER_ID);

        assertThat(savedSignedOn).isEqualTo(SIGNED_ON.toString());

        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("ENTITY_UPDATED");

            assertThat(record.subject().type()).isEqualTo("entity");

            assertThat(record.subject().id()).isEqualTo(ENTITY_ID);

            assertThat(record.scope().entityId()).isEqualTo(ENTITY_ID);

            assertThat(record.before()).isInstanceOf(Map.class);

            assertThat(record.after()).isInstanceOf(Map.class);

            Map<?, ?> before = (Map<?, ?>) record.before();

            Map<?, ?> after = (Map<?, ?>) record.after();

            assertThat(before.get("responsibleOfficerUserId")).isNull();

            assertThat(before.get("dataGovernanceSignedOn")).isNull();

            assertThat(after.get("responsibleOfficerUserId")).isEqualTo(OFFICER_ID);

            assertThat(after.get("dataGovernanceSignedOn")).isEqualTo(SIGNED_ON);
        });

        assertThat(kernel.committedEvents())
                .containsExactly(new EntityUpdated(ENTITY_ID, ENTITY_CODE, "MPCS", "ONBOARDING"));
    }

    @Test
    void theEntityCardNamesTheOfficerInsteadOfShowingTheId() {

        superuserJdbc().update("delete from security.app_user where user_id = ?", OFFICER_ID);
        superuserJdbc()
                .update(
                        "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                                + " values (?, ?, 'officer-9303', 'Nimal Perera', 'BACK_OFFICE', 'ACTIVE')",
                        OFFICER_ID,
                        ENTITY_ID);
        superuserJdbc()
                .update(
                        "update party.entity set responsible_officer_user_id = ? where entity_id = ?",
                        OFFICER_ID,
                        ENTITY_ID);

        when(currentScope.get()).thenReturn(ScopeContext.dev(CALLER_USER_ID, ENTITY_ID, null));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(CALLER_USER_ID, ENTITY_ID));
        headers.set("X-Scope-Entity", ENTITY_ID.toString());

        ResponseEntity<Map> response =
                http.exchange("/v1/party/entities/" + ENTITY_ID, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("responsibleOfficerUserId", OFFICER_ID.toString())
                .containsEntry("responsibleOfficerName", "Nimal Perera");
    }

    @Test
    void aFederationSessionReadsTheOfficersNameButNotTheUserRecord() {

        appointNimalPerera();
        // fed-accounts and fed-pricing in the demo: the Federation's own entity-wide scope, which
        // reads the society's entity row (federation_admin_read) but none of its users.
        UUID federation = superuserJdbc()
                .queryForObject("select entity_id from kernel.system_identity where singleton", UUID.class);
        ScopeContext federationOwn = ScopeContext.dev(CALLER_USER_ID, federation, null);

        ResponseEntity<Map> own = readEntityAs(federationOwn, federation);

        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody()).containsEntry("responsibleOfficerName", "Nimal Perera");
        // The name is part of the register; the user record stays the society's (m1security V0017).
        assertThat(users.getUser(OFFICER_ID, federationOwn)).isEmpty();
        // A pair that is not the entity's appointment answers nothing.
        assertThat(users.appointedOfficerName(ENTITY_ID, CALLER_USER_ID, federationOwn))
                .isEmpty();
    }

    @Test
    void aFederationViewSessionReadsTheOfficersName() {

        appointNimalPerera();
        ScopeContext federationView = new ScopeContext(
                CALLER_USER_ID,
                null,
                FEDERATION_ID,
                List.of(new Scope(FEDERATION_ID, null)),
                null,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);

        ResponseEntity<Map> response = readEntityAs(federationView, FEDERATION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("responsibleOfficerName", "Nimal Perera");
    }

    @Test
    void aSessionThatCannotReadTheEntityCannotReadTheOfficersName() {

        appointNimalPerera();
        ScopeContext anotherSociety = ScopeContext.dev(CALLER_USER_ID, OTHER_ENTITY_ID, null);

        ResponseEntity<Map> response = readEntityAs(anotherSociety, OTHER_ENTITY_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(response.getBody())).doesNotContain("Nimal Perera");
        ScopeContext none = new ScopeContext(
                CALLER_USER_ID, null, null, List.of(), null, PolicyClass.NONE, Set.of(), null, Locale.ENGLISH, null);
        assertThat(users.appointedOfficerName(ENTITY_ID, OFFICER_ID, none)).isEmpty();
    }

    private void appointNimalPerera() {

        superuserJdbc().update("delete from security.app_user where user_id = ?", OFFICER_ID);
        superuserJdbc()
                .update(
                        "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                                + " values (?, ?, 'officer-9303', 'Nimal Perera', 'BACK_OFFICE', 'ACTIVE')",
                        OFFICER_ID,
                        ENTITY_ID);
        superuserJdbc()
                .update(
                        "update party.entity set responsible_officer_user_id = ? where entity_id = ?",
                        OFFICER_ID,
                        ENTITY_ID);
    }

    private ResponseEntity<Map> readEntityAs(ScopeContext scope, UUID scopeEntity) {

        when(currentScope.get()).thenReturn(scope);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(CALLER_USER_ID, scopeEntity));
        headers.set("X-Scope-Entity", scopeEntity.toString());
        return http.exchange("/v1/party/entities/" + ENTITY_ID, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        EntityOfficerOwnership responsibleOfficerIntegrationOwnership() {

            return new EntityOfficerOwnership(new JdbcTemplate()) {

                @Override
                boolean userBelongsToEntity(UUID userId, UUID entityId) {

                    return OFFICER_ID.equals(userId) && ENTITY_ID.equals(entityId);
                }
            };
        }
    }
}
