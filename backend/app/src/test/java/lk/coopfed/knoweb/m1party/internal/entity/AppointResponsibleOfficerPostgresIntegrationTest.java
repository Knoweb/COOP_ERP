package lk.coopfed.knoweb.m1party.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.EntityUpdated;
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

    @Autowired
    private TestRestTemplate http;

    @MockBean
    private CurrentScope currentScope;

    @BeforeEach
    void seedEntity() {

        superuserJdbc().update("delete from party.entity where entity_id = ?", ENTITY_ID);

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
