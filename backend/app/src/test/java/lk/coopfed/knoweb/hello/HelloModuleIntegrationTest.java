package lk.coopfed.knoweb.hello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import lk.coopfed.knoweb.hello.api.GreetingRegistered;
import lk.coopfed.knoweb.hello.api.GreetingView;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
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

/**
 * The proof table of 17A section 12, as tests. Every module's integration test has the same
 * four groups: tenant isolation, grants, the command pipeline, and the API contract.
 *
 * <p>Everything under test goes through HTTP, as a real caller would. The caller's scope is
 * given by the development headers (see DevScopeArgumentResolver); 19A replaces them with a
 * token and these tests then send a token instead.
 */
class HelloModuleIntegrationTest extends PostgresIntegrationTest {

    // K03A_TEST_USER_ISOLATION
    private static final ThreadLocal<String> REQUEST_USER =
            ThreadLocal.withInitial(() -> UUID.randomUUID().toString());

    @org.junit.jupiter.api.BeforeEach
    void resetK03aRequestUser() {
        REQUEST_USER.set(UUID.randomUUID().toString());
    }

    private static final String URL = "/v1/hello/greetings";

    private final UUID entityA = Ids.next();
    private final UUID entityB = Ids.next();
    private final UUID federation = Ids.next();

    @Autowired
    private TestRestTemplate http;

    /** The application's own connection: coop_app, a member of app_rw, not a superuser. */
    @Autowired
    private JdbcTemplate appJdbc;

    @BeforeEach
    void emptyTable() {
        superuserJdbc().execute("TRUNCATE hello.greeting");
    }

    // ---- tenant isolation: "a user scoped to entity A cannot read entity B's greeting" ----

    @Test
    void anEntitySeesItsOwnGreetingsAndNobodyElses() {
        UUID ofA = idOf(register(entityA, "Hello from A"));
        register(entityB, "Hello from B");

        JsonNode listOfA = list(scope(entityA)).getBody();
        assertThat(listOfA).hasSize(1);
        assertThat(listOfA.get(0).get("textEn").asText()).isEqualTo("Hello from A");

        assertThat(get(ofA, scope(entityA)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Not 403: whether the row exists is itself something entity B must not learn.
        assertThat(get(ofA, scope(entityB)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void withoutAScopeNothingIsVisible() {
        register(entityA, "Hello from A");

        assertThat(list(new HttpHeaders()).getBody()).isEmpty();
    }

    @Test
    void theFederationViewSeesEveryEntity() {
        register(entityA, "Hello from A");
        register(entityB, "Hello from B");

        HttpHeaders fedView = scope(federation);
        fedView.set("X-Dev-Scope-Class", "FEDERATION_VIEW");

        assertThat(list(fedView).getBody()).hasSize(2);
    }

    @Test
    void aTransactionThatForgetsTheScopeSeesNoRows() {
        register(entityA, "Hello from A");

        // Straight SQL on the application's connection, with no ScopeContext anywhere:
        Integer seenByApplication = appJdbc.queryForObject("select count(*) from hello.greeting", Integer.class);
        Integer reallyThere = superuserJdbc().queryForObject("select count(*) from hello.greeting", Integer.class);

        assertThat(reallyThere).isEqualTo(1);
        assertThat(seenByApplication).isZero();
    }

    // ---- grants: "INSERT allowed, UPDATE denied" ----

    @Test
    void theApplicationMayNeitherUpdateNorDeleteAGreeting() {
        register(entityA, "Hello from A");

        // Spring wraps the database error; PostgreSQL's own words are the root cause.
        assertThatThrownBy(() -> appJdbc.update("update hello.greeting set status = 'CHANGED'"))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.update("delete from hello.greeting"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    // ---- command pipeline: idempotency, audit and event in one transaction ----

    @Test
    void registeringWritesOneRowOneAuditRecordAndOneEvent() {
        ResponseEntity<JsonNode> response = register(entityA, "Hello from A");
        UUID id = idOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath(URL + "/" + id);
        assertThat(rowCount()).isEqualTo(1);

        // The audit record: which catalogue code, about which row, in whose scope, and the
        // state after the change. "before" is null because a registration creates the row.
        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("HELLO_GREETING_REGISTERED");
            assertThat(record.subject().type()).isEqualTo("greeting");
            assertThat(record.subject().id()).isEqualTo(id);
            assertThat(record.scope().entityId()).isEqualTo(entityA);
            assertThat(record.before()).isNull();
            assertThat(record.after()).isInstanceOfSatisfying(GreetingView.class, after -> {
                assertThat(after.textEn()).isEqualTo("Hello from A");
                assertThat(after.ownerEntityId()).isEqualTo(entityA);
            });
        });
        // The event: identifiers only, never the texts.
        assertThat(kernel.committedEvents()).containsExactly(new GreetingRegistered(id, entityA));
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameGreetingAndRunsTheHandlerOnce() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = post(scope(entityA), key, Map.of("textEn", "Hello once"));
        ResponseEntity<JsonNode> retry = post(scope(entityA), key, Map.of("textEn", "Hello once"));

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(rowCount()).isEqualTo(1);
        assertThat(kernel.committedAudit()).hasSize(1);
        assertThat(kernel.committedEvents()).hasSize(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        String key = UUID.randomUUID().toString();
        post(scope(entityA), key, Map.of("textEn", "Hello first"));

        ResponseEntity<JsonNode> other = post(scope(entityA), key, Map.of("textEn", "Hello second"));

        assertProblem(other, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency.request_mismatch");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void aMutatingRequestWithoutAKeyIsRefused() {
        ResponseEntity<JsonNode> response = post(scope(entityA), null, Map.of("textEn", "Hello"));

        assertProblem(response, HttpStatus.BAD_REQUEST, "idempotency.key_required");
        assertThat(rowCount()).isZero();
    }

    @Test
    void whenTheEventCannotBePublishedNothingIsSaved() {
        kernel.failNextPublishWith(new IllegalStateException("outbox unavailable"));

        ResponseEntity<JsonNode> response = register(entityA, "Hello from A");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(rowCount()).isZero();
        // The audit record was written, and went away with the row it described.
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.rolledBackAudit()).hasSize(1);
    }

    // ---- guards: every guard has a failing-case test (AGENTS.md) ----

    @Test
    void registeringWithoutAScopeIsRefused() {
        ResponseEntity<JsonNode> response =
                post(new HttpHeaders(), UUID.randomUUID().toString(), Map.of("textEn", "Hello"));

        assertProblem(response, HttpStatus.BAD_REQUEST, "scope.required");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aBlankEnglishTextIsRefused() {
        ResponseEntity<JsonNode> response =
                post(scope(entityA), UUID.randomUUID().toString(), Map.of("textEn", "   "));

        assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "hello.greeting.text_required");
    }

    @Test
    void theSameTextTwiceInOneEntityIsRefusedButAnotherEntityMayUseIt() {
        register(entityA, "Hello");

        assertProblem(register(entityA, "Hello"), HttpStatus.UNPROCESSABLE_ENTITY, "hello.greeting.duplicate");
        assertThat(register(entityB, "Hello").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- API contract and i18n ----

    @Test
    void aMissingTranslationIsNullSoTheClientCanShowTheFallbackTag() {
        JsonNode created = post(
                        scope(entityA),
                        UUID.randomUUID().toString(),
                        Map.of("textEn", "Hello", "textSi", "ආයුබෝවන්", "textTa", " "))
                .getBody();

        assertThat(created.get("textSi").asText()).isEqualTo("ආයුබෝවන්");
        assertThat(created.get("textTa").isNull()).isTrue();
        assertThat(created.get("status").asText()).isEqualTo("REGISTERED");
    }

    @Test
    void aProblemIsTitledInTheCallersLanguage() {
        HttpHeaders sinhala = scope(entityA);
        sinhala.set(HttpHeaders.ACCEPT_LANGUAGE, "si");

        ResponseEntity<JsonNode> response = post(sinhala, UUID.randomUUID().toString(), Map.of("textEn", " "));

        assertThat(response.getBody().get("title").asText()).isEqualTo("සුබපැතුම ඉංග්‍රීසියෙන් ඇතුළත් කරන්න");
    }

    // ---- the slice is enforced: the kernel checks a request's shape before the controller runs ----

    @Test
    void aRequestWithoutARequiredFieldIsRefusedFieldByField() {
        ResponseEntity<JsonNode> response =
                post(scope(entityA), UUID.randomUUID().toString(), Map.of("textSi", "ආයුබෝවන්"));

        assertProblem(response, HttpStatus.BAD_REQUEST, "request.invalid");
        JsonNode errors = response.getBody().get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asText()).isEqualTo("textEn");
        assertThat(errors.get(0).get("code").asText()).isEqualTo("request.field.required");
        assertThat(errors.get(0).get("message").asText()).isEqualTo("This is required");
        // The handler never ran.
        assertThat(rowCount()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aTextLongerThanTheSliceAllowsIsRefusedInTheCallersLanguage() {
        HttpHeaders tamil = scope(entityA);
        tamil.set(HttpHeaders.ACCEPT_LANGUAGE, "ta");

        ResponseEntity<JsonNode> response =
                post(tamil, UUID.randomUUID().toString(), Map.of("textEn", "Hello", "textTa", "வ".repeat(201)));

        assertProblem(response, HttpStatus.BAD_REQUEST, "request.invalid");
        JsonNode error = response.getBody().get("errors").get(0);
        assertThat(error.get("field").asText()).isEqualTo("textTa");
        assertThat(error.get("code").asText()).isEqualTo("request.field.too_long");
        assertThat(error.get("params").get("max").asInt()).isEqualTo(200);
        assertThat(error.get("message").asText()).isEqualTo("மிக நீளமானது: அதிகபட்சம் 200");
        assertThat(rowCount()).isZero();
    }

    @Test
    void everyBrokenFieldIsReportedAtOnce() {
        ResponseEntity<JsonNode> response =
                post(scope(entityA), UUID.randomUUID().toString(), Map.of("textEn", "", "textSi", "x".repeat(201)));

        assertProblem(response, HttpStatus.BAD_REQUEST, "request.invalid");
        assertThat(response.getBody().get("errors"))
                .extracting(error ->
                        error.get("field").asText() + " " + error.get("code").asText())
                .containsExactly("textEn request.field.too_short", "textSi request.field.too_long");
    }

    @Test
    void aBodyThatCannotBeReadIsRefused() {
        HttpHeaders headers = scope(entityA);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> response =
                http.exchange(URL, HttpMethod.POST, new HttpEntity<>("{ \"textEn\": ", headers), JsonNode.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "request.malformed");
    }

    @Test
    void anIdThatIsNotAUuidIsRefused() {
        ResponseEntity<JsonNode> response =
                http.exchange(URL + "/not-a-uuid", HttpMethod.GET, new HttpEntity<>(scope(entityA)), JsonNode.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "request.invalid");
        assertThat(response.getBody().get("errors").get(0).get("field").asText())
                .isEqualTo("id");
        assertThat(response.getBody().get("errors").get(0).get("code").asText()).isEqualTo("request.field.invalid");
    }

    // ---- time: an instant is stored exactly, whatever zone the server runs in ----

    @Test
    void theRegistrationInstantIsExactUnderANonUtcServerZone() {
        // The premise, set for every integration test in build.gradle.kts. Under UTC this test
        // could not fail, so it insists on the zone that once hid a five-and-a-half-hour error.
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Colombo");

        Instant before = Instant.now();
        JsonNode created = register(entityA, "Hello on time").getBody();
        UUID id = UUID.fromString(created.get("id").asText());

        String onTheWire = created.get("createdAt").asText();
        Instant fromApi = Instant.parse(onTheWire);
        Instant inDatabase = superuserJdbc()
                .queryForObject(
                        "select created_at from hello.greeting where id = ?",
                        (row, n) -> row.getObject(1, OffsetDateTime.class).toInstant(),
                        id);

        // The API speaks UTC, and says the same instant the database holds.
        assertThat(onTheWire).endsWith("Z");
        assertThat(fromApi).isEqualTo(inDatabase);
        // And that instant is now: not now plus or minus the Colombo offset. The margin only
        // allows for the clock of the Docker machine differing a little from this one.
        assertThat(Duration.between(before, inDatabase).abs()).isLessThan(Duration.ofMinutes(10));
    }

    // ---- helpers ----

    private static HttpHeaders scope(UUID entity) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Dev-User", REQUEST_USER.get());
        headers.set("X-Scope-Entity", entity.toString());
        return headers;
    }

    private ResponseEntity<JsonNode> register(UUID entity, String textEn) {
        return post(scope(entity), UUID.randomUUID().toString(), Map.of("textEn", textEn));
    }

    private ResponseEntity<JsonNode> post(HttpHeaders headers, String idempotencyKey, Map<String, String> body) {
        HttpHeaders all = new HttpHeaders();
        all.addAll(headers);
        all.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            all.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, all), JsonNode.class);
    }

    private ResponseEntity<JsonNode> list(HttpHeaders headers) {
        return http.exchange(URL, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(UUID id, HttpHeaders headers) {
        return http.exchange(URL + "/" + id, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private static UUID idOf(ResponseEntity<JsonNode> response) {
        return UUID.fromString(response.getBody().get("id").asText());
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private static int rowCount() {
        return superuserJdbc().queryForObject("select count(*) from hello.greeting", Integer.class);
    }
}
