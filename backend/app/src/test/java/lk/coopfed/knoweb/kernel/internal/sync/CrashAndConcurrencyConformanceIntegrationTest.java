package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Doc 32 section 11, the two cases about what happens inside central while a batch is being
 * ingested: "Crash mid-batch at central" (the cursor at the last committed chunk; a resend from
 * cursor + 1; no duplicates, no loss) and "Concurrent batches for one device" (the second
 * rejected with 409; the first completes). An {@link IngestionProbe} reaches inside the chunk
 * transaction: it throws there to crash, or waits there to hold the first batch open.
 */
class CrashAndConcurrencyConformanceIntegrationTest extends SyncIntegrationTest {

    /** What the probe does at a chunk's commit; the tests set it, no-op otherwise. */
    static volatile IngestionProbe behaviour = (device, chunk) -> {};

    @TestConfiguration
    static class Probe {
        @Bean
        IngestionProbe ingestionProbe() {
            return (device, chunk) -> behaviour.beforeChunkCommit(device, chunk);
        }
    }

    @AfterEach
    void probeOff() {
        behaviour = (device, chunk) -> {};
    }

    @Test
    void crashMidBatchLeavesTheCursorAtTheLastCommittedChunkAndTheResendLosesNothing() {
        List<ObjectNode> events = events(1, 120); // three chunks of fifty (doc 32 DR-5)
        behaviour = (device, chunk) -> {
            if (chunk == 1) {
                throw new IllegalStateException("The instance died before the second chunk committed");
            }
        };

        ResponseEntity<JsonNode> crashed = upload(Ids.next(), 1, events);

        assertThat(crashed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(cursor()).isEqualTo(50);
        assertThat(outboxSequences()).hasSize(50);

        behaviour = (device, chunk) -> {};
        // The till knows nothing was acknowledged; it resends the batch under a new id.
        JsonNode ack = upload(Ids.next(), 1, events).getBody();

        assertThat(ack.path("last_applied_seq").asLong()).isEqualTo(120);
        assertThat(outcomes(ack).subList(0, 50)).containsOnly("DUPLICATE");
        assertThat(outcomes(ack).subList(50, 120)).containsOnly("APPLIED");
        List<Long> sequences = outboxSequences();
        assertThat(sequences).hasSize(120).doesNotHaveDuplicates();
        assertThat(sequences.getFirst()).isEqualTo(1L);
        assertThat(sequences.getLast()).isEqualTo(120L);
    }

    @Test
    void aSecondBatchForADeviceWhileTheFirstIsInFlightIsRefusedAndTheFirstCompletes() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondAnswered = new CountDownLatch(1);
        behaviour = (device, chunk) -> {
            firstInside.countDown();
            try {
                secondAnswered.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        UUID firstBatch = Ids.next();
        List<ObjectNode> events = events(1, 3);

        CompletableFuture<ResponseEntity<JsonNode>> first =
                CompletableFuture.supplyAsync(() -> upload(firstBatch, 1, events));
        assertThat(firstInside.await(30, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<JsonNode> second = upload(Ids.next(), 1, events);
        secondAnswered.countDown();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().path("code").asText()).isEqualTo("sync.batch_in_flight");
        assertThat(second.getBody().path("params").path("in_flight_batch_id").asText())
                .isEqualTo(firstBatch.toString());

        ResponseEntity<JsonNode> completed = first.get(60, TimeUnit.SECONDS);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(completed.getBody())).containsOnly("APPLIED");
        assertThat(cursor()).isEqualTo(3);
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void aClaimLeftByADeadInstanceIsTakenOverAfterTheInFlightTimeout() {
        superuserJdbc()
                .update(
                        "update kernel.device_sync_cursor set in_flight_batch_id = ?, in_flight_since = now() - interval '1 hour'"
                                + " where device_id = ?",
                        Ids.next(),
                        DEVICE);

        ResponseEntity<JsonNode> response = upload(Ids.next(), 1, events(1, 2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cursor()).isEqualTo(2);
    }

    @Test
    void completingABatchWhoseClaimWasTakenOverIsTheInFlightConflictNotAnError() {
        // A slow ingestion outlived the in-flight timeout and a resend on another instance took
        // the cursor; the first then finishes its last chunk and calls complete().
        UUID takenOverBy = Ids.next();
        UUID slow = Ids.next();
        superuserJdbc()
                .update(
                        "update kernel.device_sync_cursor set in_flight_batch_id = ?, in_flight_since = now() where device_id = ?",
                        takenOverBy,
                        DEVICE);
        ScopeContext device = deviceScope();

        assertThatThrownBy(() -> systemScope.inScope(
                        device, () -> transactions.complete(device, slow, 1, 1, new Ack.Stored(1, List.of()), 1, 0, 0)))
                .isInstanceOf(IngestTransactions.ClaimLost.class)
                .satisfies(lost ->
                        assertThat(((IngestTransactions.ClaimLost) lost).holder).isEqualTo(takenOverBy));
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Autowired
    IngestTransactions transactions;

    @Autowired
    SystemScope systemScope;

    /** The device's own scope, as the token gives it. */
    private static ScopeContext deviceScope() {
        Scope scope = new Scope(ENTITY, SHOP);
        return new ScopeContext(
                null,
                DEVICE,
                ENTITY,
                List.of(scope),
                scope,
                PolicyClass.DEVICE,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }
}
