package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The done criterion of S0-02: the application starts under each of its three roles, with
 * health and metrics under all of them (17A section 4.1). And the one difference that exists
 * today: only the web role serves the API (which, without a token, answers 401: it is there
 * and asks for a sign-in; a worker answers 404: on that instance it does not exist).
 *
 * <p>Each nested class starts the application once under its role, against the same database.
 * The two worker contexts are closed when their tests end: Spring would otherwise keep them
 * for the rest of the run, with the outbox relay and the job scheduler polling the shared
 * database every half second, and a relay of another test then found the advisory lock
 * taken (the event backbone and relay lock tests failed now and then in full runs only).
 */
class RuntimeRolesIntegrationTest {

    private static final String API = "/v1/hello/greetings";

    @Nested
    class WebRole extends PostgresIntegrationTest {

        @Autowired
        private TestRestTemplate http;

        @Autowired
        private Environment environment;

        @Test
        void isTheDefaultAndServesTheApi() {
            // Nobody set COOP_ERP_ROLE: application.yml makes "web" the active profile.
            assertThat(environment.getActiveProfiles()).containsExactly("web");
            assertThat(http.getForEntity(API, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertHealthAndMetrics(http);
        }
    }

    @Nested
    @ActiveProfiles("worker")
    @DirtiesContext
    class WorkerRole extends PostgresIntegrationTest {

        @Autowired
        private TestRestTemplate http;

        @Test
        void startsAndServesHealthAndMetricsButNoApi() {
            assertThat(http.getForEntity(API, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertHealthAndMetrics(http);
        }
    }

    @Nested
    @ActiveProfiles("ingest")
    class IngestRole extends PostgresIntegrationTest {

        @Autowired
        private TestRestTemplate http;

        @Test
        void startsAndServesHealthAndMetricsButNoApi() {
            assertThat(http.getForEntity(API, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertHealthAndMetrics(http);
        }
    }

    @Nested
    @ActiveProfiles({"web", "worker"})
    @DirtiesContext
    class WebAndWorkerTogether extends PostgresIntegrationTest {

        @Autowired
        private TestRestTemplate http;

        @Test
        void serveTheApiAsTheLocalStackDoes() {
            assertThat(http.getForEntity(API, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    private static void assertHealthAndMetrics(TestRestTemplate http) {
        assertThat(http.getForEntity("/actuator/health", String.class).getBody())
                .contains("\"status\":\"UP\"");
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Spring Boot switches metric exporters off inside tests, so /actuator/prometheus is
        // checked by tools/smoke.mjs against the running stack; here, that metrics exist at all.
        assertThat(http.getForEntity("/actuator/metrics/jvm.memory.used", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
