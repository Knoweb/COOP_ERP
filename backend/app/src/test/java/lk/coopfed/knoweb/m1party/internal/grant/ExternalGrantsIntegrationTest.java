package lk.coopfed.knoweb.m1party.internal.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ExpireExternalGrant;
import lk.coopfed.knoweb.m1party.api.ExternalGrantExpired;
import lk.coopfed.knoweb.m1party.api.ExternalGrantIssued;
import lk.coopfed.knoweb.m1party.api.ExternalGrantRevoked;
import lk.coopfed.knoweb.m1party.query.ExternalGrantQueries;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M1-09: external grants, doc 21 flow 6.5 ("Regulator access for an inspection") end to end, the
 * guards of 21A section 6, the resolution of an EXTERNAL_TIMEBOXED principal's entities, the
 * expiry job, and what such a principal reads under row-level security.
 *
 * <p>The external user is written here as the superuser: CreateUser is M1-07. The Federation
 * owns the user, as it does in the flow ("Federation admin creates an EXTERNAL user").
 */
class ExternalGrantsIntegrationTest extends PostgresIntegrationTest {

    private static final String GRANTS = "/v1/security/external-grants";

    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-00000009f001");
    private static final UUID MPCS_A = UUID.fromString("00000000-0000-0000-0000-00000009a001");
    private static final UUID MPCS_B = UUID.fromString("00000000-0000-0000-0000-00000009b001");

    private static final UUID FED_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000090001");
    private static final UUID REGULATOR = UUID.fromString("00000000-0000-0000-0000-000000090002");
    private static final UUID CLERK = UUID.fromString("00000000-0000-0000-0000-000000090003");
    private static final UUID RETIRED_AUDITOR = UUID.fromString("00000000-0000-0000-0000-000000090004");
    private static final UUID SECOND_AUDITOR = UUID.fromString("00000000-0000-0000-0000-000000090005");

    private static final UUID ROLE_A = UUID.fromString("00000000-0000-0000-0000-0000000900a1");
    private static final UUID ROLE_B = UUID.fromString("00000000-0000-0000-0000-0000000900b1");
    private static final UUID SOD_A = UUID.fromString("00000000-0000-0000-0000-0000000900a2");
    private static final UUID SOD_B = UUID.fromString("00000000-0000-0000-0000-0000000900b2");

    private static final List<UUID> USERS = List.of(FED_ADMIN, REGULATOR, CLERK, RETIRED_AUDITOR, SECOND_AUDITOR);

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ExternalGrantQueries queries;

    @Autowired
    private ExternalGrantExpiryJob expiryJob;

    @Autowired
    private Handles<ExpireExternalGrant, UUID> expire;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private Clock clock;

    @BeforeEach
    void theFederationTwoSocietiesAndTheirPeople() {
        JdbcTemplate admin = superuserJdbc();
        removeSecurityRows(admin);
        admin.execute("truncate table party.entity_relationship, party.entity_party_directory,"
                + " party.federation_identity, party.entity cascade");

        insertEntity(admin, FEDERATION, "FED001", "FEDERATION", "Cooperative Federation");
        insertEntity(admin, MPCS_A, "M9A01", "MPCS", "Society A");
        insertEntity(admin, MPCS_B, "M9B01", "MPCS", "Society B");

        insertUser(admin, FED_ADMIN, "fed-admin-m109", "BACK_OFFICE", "ACTIVE");
        insertUser(admin, REGULATOR, "regulator-m109", "EXTERNAL", "ACTIVE");
        insertUser(admin, CLERK, "clerk-m109", "BACK_OFFICE", "ACTIVE");
        insertUser(admin, RETIRED_AUDITOR, "retired-m109", "EXTERNAL", "DEACTIVATED");
        insertUser(admin, SECOND_AUDITOR, "auditor-m109", "EXTERNAL", "PENDING");

        // What an inspector of society A may read: A's duties, who holds them, its segregation pairs.
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en) values (?, ?, ?)",
                ROLE_A,
                MPCS_A,
                "Shop in charge A");
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en) values (?, ?, ?)",
                ROLE_B,
                MPCS_B,
                "Shop in charge B");
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id) values (?, ?, ?)",
                CLERK,
                ROLE_A,
                MPCS_A);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id) values (?, ?, ?)",
                CLERK,
                ROLE_B,
                MPCS_B);
        admin.update(
                "insert into security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)"
                        + " values (?, 'gov.entity.activate', 'gov.entity.register', 'INSTANCE', ?)",
                SOD_A,
                MPCS_A);
        admin.update(
                "insert into security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)"
                        + " values (?, 'gov.entity.activate', 'gov.entity.register', 'INSTANCE', ?)",
                SOD_B,
                MPCS_B);
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        removeSecurityRows(superuserJdbc());
    }

    // ---- flow 6.5 ---------------------------------------------------------------------------

    @Test
    void scenario65TheRegulatorGrantLifecycle() {
        Instant until = clock.instant().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);

        // Federation admin: GrantExternalView with scope and valid_until.
        ResponseEntity<JsonNode> issued =
                post(GRANTS, grantBody(REGULATOR, List.of(MPCS_A), null, until, "Inspection"));

        assertThat(issued.getStatusCode()).as(String.valueOf(issued.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID grantId = UUID.fromString(issued.getBody().get("grantId").asText());
        assertThat(issued.getBody().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(issued.getBody().get("scopeEntityIds").get(0).asText()).isEqualTo(MPCS_A.toString());
        assertThat(Instant.parse(issued.getBody().get("validUntil").asText())).isEqualTo(until);

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select status, owner_entity_id, reason from security.external_grant where grant_id = ?",
                        grantId);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("owner_entity_id", FEDERATION);

        AuditRecord audit = kernel.committedAudit().get(0);
        assertThat(kernel.committedAudit()).hasSize(1);
        assertThat(audit.eventType()).isEqualTo("EXTERNAL_GRANT_ISSUED");
        assertThat(audit.subject().type()).isEqualTo("external_grant");
        assertThat(audit.subject().id()).isEqualTo(grantId);
        assertThat(audit.scope().entityId()).isEqualTo(FEDERATION);
        assertThat(audit.reason()).isEqualTo("Inspection");
        assertThat(kernel.committedEvents())
                .singleElement()
                .isInstanceOfSatisfying(ExternalGrantIssued.class, event -> {
                    assertThat(event.grantId()).isEqualTo(grantId);
                    assertThat(event.granteeUserId()).isEqualTo(REGULATOR);
                    assertThat(event.scopeEntityIds()).containsExactly(MPCS_A);
                    assertThat(event.validUntil()).isEqualTo(until);
                });

        // Scope resolution for the external user: the granted society, and it alone.
        Set<UUID> granted = queries.activeGrantedEntities(REGULATOR, clock.instant());
        assertThat(granted).containsExactly(MPCS_A);

        // What that principal reads: society A's rows, nothing of B, nothing to write.
        assertThat(externalReads(granted))
                .isEqualTo(Map.of("role", List.of(MPCS_A), "user_role", List.of(MPCS_A), "sod_pair", List.of(MPCS_A)));
        assertThatThrownBy(() -> asExternal(
                        granted,
                        () -> jdbc.update(
                                "insert into security.role (role_id, owner_entity_id, name_en) values (gen_random_uuid(), ?, 'x')",
                                MPCS_A)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // The Federation's register lists it.
        ResponseEntity<JsonNode> register = get(GRANTS);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(register.getBody().get("items").findValuesAsText("grantId")).contains(grantId.toString());

        // Failure path: a grant beyond twelve months is refused, and nothing is written.
        kernel.reset();
        Instant tooLate =
                clock.instant().atOffset(ZoneOffset.UTC).plusMonths(13).toInstant();
        ResponseEntity<JsonNode> refused = post(GRANTS, grantBody(REGULATOR, List.of(MPCS_B), null, tooLate, "Audit"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("m1.grant.window_too_long");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(grantsOf(REGULATOR)).isEqualTo(1);

        // Failure path: revocation is immediate.
        kernel.reset();
        ResponseEntity<JsonNode> revoked =
                post(GRANTS + "/" + grantId + "/revoke", Map.of("reason", "Inspection closed"));
        assertThat(revoked.getStatusCode())
                .as(String.valueOf(revoked.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusOf(grantId)).isEqualTo("REVOKED");
        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("EXTERNAL_GRANT_REVOKED");
            assertThat(record.reason()).isEqualTo("Inspection closed");
        });
        assertThat(kernel.committedEvents())
                .singleElement()
                .isInstanceOfSatisfying(ExternalGrantRevoked.class, event -> {
                    assertThat(event.grantId()).isEqualTo(grantId);
                    assertThat(event.scopeEntityIds()).containsExactly(MPCS_A);
                });
        Set<UUID> afterRevoke = queries.activeGrantedEntities(REGULATOR, clock.instant());
        assertThat(afterRevoke).isEmpty();
        assertThat(externalReads(afterRevoke))
                .isEqualTo(Map.of("role", List.of(), "user_role", List.of(), "sod_pair", List.of()));

        // Expiry by clock: a grant whose window has passed admits nothing before the job runs,
        // and the job marks it, audits it and announces it.
        UUID ended = insertGrant(REGULATOR, MPCS_B, Duration.ofDays(10), Duration.ofHours(-1), "ACTIVE");
        assertThat(queries.activeGrantedEntities(REGULATOR, clock.instant())).isEmpty();

        kernel.reset();
        assertThat(expiryJob.expireEndedGrants(systemScope(FEDERATION))).isEqualTo(1);

        assertThat(statusOf(ended)).isEqualTo("EXPIRED");
        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("EXTERNAL_GRANT_EXPIRED");
            assertThat(record.subject().id()).isEqualTo(ended);
            assertThat(record.scope().entityId()).isEqualTo(FEDERATION);
        });
        assertThat(kernel.committedEvents())
                .singleElement()
                .isInstanceOfSatisfying(ExternalGrantExpired.class, event -> assertThat(event.grantId())
                        .isEqualTo(ended));

        // Nothing left to expire: a second run does nothing.
        assertThat(expiryJob.expireEndedGrants(systemScope(FEDERATION))).isZero();
    }

    // ---- guards of GrantExternalView -----------------------------------------------------------

    @Test
    void onlyTheFederationGrants() {
        ResponseEntity<JsonNode> response =
                post(GRANTS, grantBody(REGULATOR, List.of(MPCS_A), null, inDays(5), "Inspection"), MPCS_A);

        assertRefused(response, "m1.grant.federation_required");
    }

    @Test
    void theGranteeMustBeAnExternalUser() {
        assertRefused(
                post(GRANTS, grantBody(CLERK, List.of(MPCS_A), null, inDays(5), "Inspection")),
                "m1.grant.grantee_not_external");
    }

    @Test
    void theGranteeMustExist() {
        assertRefused(
                post(GRANTS, grantBody(UUID.randomUUID(), List.of(MPCS_A), null, inDays(5), "Inspection")),
                "m1.grant.grantee_not_found");
    }

    @Test
    void aDeactivatedGranteeGetsNothing() {
        assertRefused(
                post(GRANTS, grantBody(RETIRED_AUDITOR, List.of(MPCS_A), null, inDays(5), "Inspection")),
                "m1.grant.grantee_deactivated");
    }

    @Test
    void everyEntityOfTheScopeMustExist() {
        assertRefused(
                post(GRANTS, grantBody(REGULATOR, List.of(MPCS_A, UUID.randomUUID()), null, inDays(5), "Inspection")),
                "m1.grant.entity_not_found");
    }

    @Test
    void aWindowThatHasAlreadyEndedIsRefused() {
        assertRefused(
                post(
                        GRANTS,
                        grantBody(
                                REGULATOR,
                                List.of(MPCS_A),
                                null,
                                clock.instant().minusSeconds(60),
                                "Late")),
                "m1.grant.window_invalid");
    }

    @Test
    void aBlankReasonIsRefused() {
        assertRefused(
                post(GRANTS, grantBody(REGULATOR, List.of(MPCS_A), null, inDays(5), "   ")),
                "m1.grant.reason_required");
    }

    @Test
    void aGrantMayStartLaterAndAdmitsNothingUntilThen() {
        Instant from = inDays(2);
        ResponseEntity<JsonNode> response =
                post(GRANTS, grantBody(SECOND_AUDITOR, List.of(MPCS_A, MPCS_B), from, inDays(20), "Booked audit"));

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        assertThat(queries.activeGrantedEntities(SECOND_AUDITOR, clock.instant()))
                .isEmpty();
        assertThat(queries.activeGrantedEntities(SECOND_AUDITOR, from.plusSeconds(1)))
                .containsExactlyInAnyOrder(MPCS_A, MPCS_B);
    }

    // ---- guards of RevokeExternalView and ExpireExternalGrant -----------------------------------

    @Test
    void anUnknownGrantCannotBeRevoked() {
        assertRefused(post(GRANTS + "/" + UUID.randomUUID() + "/revoke", Map.of("reason", "x")), "m1.grant.not_found");
    }

    @Test
    void aRevokedGrantCannotBeRevokedAgain() {
        UUID grantId = insertGrant(REGULATOR, MPCS_A, Duration.ofDays(1), Duration.ofDays(3), "REVOKED");

        assertRefused(post(GRANTS + "/" + grantId + "/revoke", Map.of("reason", "again")), "m1.grant.not_active");
    }

    @Test
    void aRevocationNeedsAReason() {
        UUID grantId = insertGrant(REGULATOR, MPCS_A, Duration.ofDays(1), Duration.ofDays(3), "ACTIVE");

        assertRefused(post(GRANTS + "/" + grantId + "/revoke", Map.of("reason", " ")), "m1.grant.reason_required");
        assertThat(statusOf(grantId)).isEqualTo("ACTIVE");
    }

    @Test
    void onlyTheFederationRevokes() {
        UUID grantId = insertGrant(REGULATOR, MPCS_A, Duration.ofDays(1), Duration.ofDays(3), "ACTIVE");

        assertRefused(
                post(GRANTS + "/" + grantId + "/revoke", Map.of("reason", "mine"), MPCS_A),
                "m1.grant.federation_required");
    }

    @Test
    void aGrantStillRunningIsNotExpired() {
        UUID grantId = insertGrant(REGULATOR, MPCS_A, Duration.ofDays(1), Duration.ofDays(3), "ACTIVE");
        ScopeContext federation = ScopeContext.dev(null, FEDERATION, null);

        assertThatThrownBy(() -> expire.handle(new ExpireExternalGrant(grantId), federation))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m1.grant.not_due"));
        assertThat(statusOf(grantId)).isEqualTo("ACTIVE");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void withoutASystemScopeTheJobChangesNothing() {
        UUID ended = insertGrant(REGULATOR, MPCS_A, Duration.ofDays(10), Duration.ofHours(-1), "ACTIVE");

        assertThat(expiryJob.expireEndedGrants(noSystemScope())).isZero();
        assertThat(statusOf(ended)).isEqualTo("ACTIVE");
    }

    @Test
    void theExpiryJobIsInTheCatalogue() {
        Map<String, Object> job = superuserJdbc()
                .queryForMap("select module, cron from kernel.scheduled_job where name = 'external-grant-expiry'");

        assertThat(job).containsEntry("module", "m1party").containsEntry("cron", "0 5 * * * *");
    }

    // ---- resolution -------------------------------------------------------------------------

    @Test
    void aUserResolvesItsOwnGrantsOnly() {
        insertGrant(REGULATOR, MPCS_A, Duration.ofDays(1), Duration.ofDays(3), "ACTIVE");
        insertGrant(SECOND_AUDITOR, MPCS_B, Duration.ofDays(1), Duration.ofDays(3), "ACTIVE");
        insertGrant(REGULATOR, MPCS_B, Duration.ofDays(1), Duration.ofDays(3), "EXPIRED");

        assertThat(queries.activeGrantedEntities(REGULATOR, clock.instant())).containsExactly(MPCS_A);
        assertThat(queries.activeGrantedEntities(SECOND_AUDITOR, clock.instant()))
                .containsExactly(MPCS_B);
        assertThat(queries.activeGrantedEntities(CLERK, clock.instant())).isEmpty();
        assertThat(queries.activeGrantedEntities(null, clock.instant())).isEmpty();
    }

    @Test
    void resolvingInsideAnotherTransactionLeavesThatTransactionsScopeAlone() {
        insertGrant(REGULATOR, MPCS_A, Duration.ofDays(1), Duration.ofDays(3), "ACTIVE");

        String classAfter = new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("select set_config('app.scope_class', 'OWN', true)", String.class);
            jdbc.queryForObject("select set_config('app.scope_entity_id', ?, true)", String.class, MPCS_B.toString());

            assertThat(queries.activeGrantedEntities(REGULATOR, clock.instant()))
                    .containsExactly(MPCS_A);

            String scopeClass = jdbc.queryForObject("select current_setting('app.scope_class', true)", String.class)
                    + "/" + jdbc.queryForObject("select current_setting('app.scope_entity_id', true)", String.class);
            status.setRollbackOnly();
            return scopeClass;
        });

        assertThat(classAfter).isEqualTo("OWN/" + MPCS_B);
    }

    @Test
    void anExpiredOrEmptyGrantReadsNothing() {
        assertThat(externalReads(Set.of()))
                .isEqualTo(Map.of("role", List.of(), "user_role", List.of(), "sod_pair", List.of()));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The owning entity of each row an EXTERNAL_TIMEBOXED session with these entities reads. */
    private Map<String, List<UUID>> externalReads(Set<UUID> granted) {
        return asExternal(granted, () -> {
            Map<String, List<UUID>> reads = new HashMap<>();
            reads.put(
                    "role",
                    jdbc.queryForList(
                            "select owner_entity_id from security.role where owner_entity_id is not null order by 1",
                            UUID.class));
            reads.put(
                    "user_role",
                    jdbc.queryForList("select scope_entity_id from security.user_role order by 1", UUID.class));
            reads.put(
                    "sod_pair",
                    jdbc.queryForList(
                            "select owner_entity_id from security.sod_pair where owner_entity_id is not null order by 1",
                            UUID.class));
            return reads;
        });
    }

    /** A transaction as the kernel sets it for an external principal (ScopeConnectionCustomizer). */
    private <T> T asExternal(Set<UUID> granted, Supplier<T> work) {
        String setting = granted.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("select set_config('app.user_id', ?, true)", String.class, REGULATOR.toString());
            jdbc.queryForObject(
                    "select set_config('app.scope_entity_id', ?, true)", String.class, FEDERATION.toString());
            jdbc.queryForObject("select set_config('app.scope_location_id', '', true)", String.class);
            jdbc.queryForObject("select set_config('app.scope_class', 'EXTERNAL_TIMEBOXED', true)", String.class);
            jdbc.queryForObject("select set_config('app.granted_entities', ?, true)", String.class, setting);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private static JobExecution systemScope(UUID entity) {
        return execution(Optional.of(ScopeContext.dev(null, entity, null)));
    }

    private static JobExecution noSystemScope() {
        return execution(Optional.empty());
    }

    private static JobExecution execution(Optional<ScopeContext> scope) {
        UUID runId = UUID.randomUUID();
        return new JobExecution() {
            @Override
            public UUID runId() {
                return runId;
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return scope;
            }
        };
    }

    private Instant inDays(int days) {
        return clock.instant().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Map<String, Object> grantBody(
            UUID grantee, List<UUID> entities, Instant from, Instant until, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("granteeUserId", grantee.toString());
        body.put("scopeEntityIds", entities.stream().map(UUID::toString).toList());
        if (from != null) {
            body.put("validFrom", from.toString());
        }
        body.put("validUntil", until.toString());
        body.put("reason", reason);
        return body;
    }

    private void assertRefused(ResponseEntity<JsonNode> response, String code) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private ResponseEntity<JsonNode> post(String url, Object body) {
        return post(url, body, FEDERATION);
    }

    private ResponseEntity<JsonNode> post(String url, Object body, UUID asEntity) {
        HttpHeaders headers = headers(asEntity);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String url) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(FEDERATION)), JsonNode.class);
    }

    private static HttpHeaders headers(UUID asEntity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(FED_ADMIN, asEntity));
        headers.set("X-Scope-Entity", asEntity.toString());
        return headers;
    }

    /** A grant written as the superuser, its window placed around now: from now minus {@code ago}, until now plus {@code ahead}. */
    private UUID insertGrant(UUID grantee, UUID entity, Duration ago, Duration ahead, String status) {
        UUID grantId = UUID.randomUUID();
        Instant now = clock.instant();
        superuserJdbc()
                .update(
                        """
                        insert into security.external_grant (
                            grant_id, grantee_user_id, scope_entity_ids, valid_from, valid_until, reason, status,
                            owner_entity_id
                        ) values (?, ?, array[?]::uuid[], ?, ?, 'fixture', ?, ?)
                        """,
                        grantId,
                        grantee,
                        entity,
                        now.minus(ago).atOffset(ZoneOffset.UTC),
                        now.plus(ahead).atOffset(ZoneOffset.UTC),
                        status,
                        FEDERATION);
        return grantId;
    }

    private static String statusOf(UUID grantId) {
        return superuserJdbc()
                .queryForObject("select status from security.external_grant where grant_id = ?", String.class, grantId);
    }

    private static int grantsOf(UUID grantee) {
        return superuserJdbc()
                .queryForObject(
                        "select count(*) from security.external_grant where grantee_user_id = ?",
                        Integer.class,
                        grantee);
    }

    private static void insertEntity(JdbcTemplate admin, UUID id, String code, String type, String name) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?)",
                id,
                code,
                type,
                name);
    }

    private static void insertUser(JdbcTemplate admin, UUID id, String username, String kind, String status) {
        admin.update(
                """
                insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)
                values (?, ?, ?, ?, ?, ?)
                """,
                id,
                FEDERATION,
                username,
                username,
                kind,
                status);
    }

    private static void removeSecurityRows(JdbcTemplate admin) {
        String users = USERS.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        admin.execute("delete from security.external_grant where grantee_user_id in (" + users + ")");
        admin.execute("delete from security.user_role where user_id in (" + users + ")");
        admin.update("delete from security.sod_pair where sod_pair_id in (?, ?)", SOD_A, SOD_B);
        admin.update("delete from security.role where role_id in (?, ?)", ROLE_A, ROLE_B);
        admin.execute("delete from security.app_user where user_id in (" + users + ")");
    }
}
