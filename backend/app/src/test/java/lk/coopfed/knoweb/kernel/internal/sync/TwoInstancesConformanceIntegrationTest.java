package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.CoopErpApplication;
import lk.coopfed.knoweb.kernel.sync.web.generated.EventOutcome;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Doc 32 section 11, Two instances: "batches from one device alternate between instances;
 * ordering and idempotency hold" (19A section 8: "two-instance ingestion of one device
 * alternating instances"). A second instance of the application is started on the same database
 * with the same settings as the test's own, and the till's batches go to one and the other in
 * turn, as a load balancer may send them. Nothing of a device's sync state lives in an instance:
 * the cursor, the event ledger and the acknowledgement are rows, so either instance answers.
 */
class TwoInstancesConformanceIntegrationTest extends SyncIntegrationTest {

    /** The settings the test context was started with, which the second instance takes too. */
    private static final List<String> SHARED_SETTINGS = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "coop-erp.migration.url",
            "coop-erp.migration.user",
            "coop-erp.migration.password",
            "coop-erp.relay.url",
            "coop-erp.relay.user",
            "coop-erp.relay.password",
            "management.health.rabbit.enabled",
            "coop-erp.i18n.strict-missing-ids",
            "coop-erp.rabbit.cache-fanout.enabled",
            "coop-erp.security.oidc.issuer",
            "coop-erp.security.oidc.jwk-set-uri",
            "coop-erp.system.entity-id");

    private static ConfigurableApplicationContext secondInstance;

    @Autowired
    Environment environment;

    @Autowired
    TillSigner signer;

    @AfterAll
    static void stopTheSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
            secondInstance = null;
        }
    }

    @Test
    void batchesOfOneTillAlternateBetweenInstancesAndOrderAndIdempotencyHold() {
        TestRestTemplate first = http;
        TestRestTemplate second = secondInstance();
        TillSimulator till = new TillSimulator(first, json, DEVICE, SHOP, signer.publicKeyBase64());
        till.recordSales(40);

        List<Long> acknowledged = new ArrayList<>();
        for (int turn = 0; turn < 4; turn++) {
            till.talkTo(turn % 2 == 0 ? first : second);
            acknowledged.add(till.uploadOnce(10).getLastAppliedSeq());
        }

        assertThat(acknowledged).containsExactly(10L, 20L, 30L, 40L);
        assertThat(cursor()).isEqualTo(40);
        assertThat(outboxSequences()).hasSize(40).isSorted().doesNotHaveDuplicates();

        // The acknowledgement of the last batch is lost: the till sends it again, to the other
        // instance, which answers from state and applies nothing twice.
        SyncAck resent = resendOfLastBatch(second);
        assertThat(resent.getLastAppliedSeq()).isEqualTo(40);
        assertThat(resent.getOutcomes())
                .extracting(EventOutcome::getOutcome)
                .containsOnly(EventOutcome.OutcomeEnum.DUPLICATE);
        assertThat(outboxSequences()).hasSize(40);
    }

    /** Events 31..40 sent again, as a till does whose acknowledgement never arrived. */
    private SyncAck resendOfLastBatch(TestRestTemplate instance) {
        ObjectNode body = batch(UUID.randomUUID(), 31, events(31, 40));
        ResponseEntity<SyncAck> answer = instance.exchange(
                "/v1/sync/devices/" + DEVICE + "/batches",
                HttpMethod.POST,
                new HttpEntity<>(body, headers()),
                SyncAck.class);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        return answer.getBody();
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = TestIdentityProvider.deviceHeaders(DEVICE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    private TestRestTemplate secondInstance() {
        if (secondInstance == null) {
            List<String> properties = new ArrayList<>();
            for (String key : SHARED_SETTINGS) {
                String value = environment.getProperty(key);
                if (value != null) {
                    properties.add("--" + key + "=" + value);
                }
            }
            properties.add("--server.port=0");
            secondInstance = new SpringApplicationBuilder(CoopErpApplication.class)
                    .profiles(environment.getActiveProfiles())
                    .run(properties.toArray(String[]::new));
        }
        int port = ((ServletWebServerApplicationContext) secondInstance)
                .getWebServer()
                .getPort();
        return new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port));
    }
}
