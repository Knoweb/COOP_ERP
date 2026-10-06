package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.junit.jupiter.api.Test;

/**
 * The rate limiter of doc 32 section 9 on its own, with a clock the test moves: per device,
 * batches per minute and bytes per hour; per instance, batches at the same time; 429
 * {@code sync.rate_limited} with {@code retry_after}; one device's spending never costs another.
 */
class SyncRateLimiterTest {

    private static final UUID TILL_A = UUID.fromString("0190a800-0000-7000-8000-00000000c001");
    private static final UUID TILL_B = UUID.fromString("0190a800-0000-7000-8000-00000000c002");

    private final MovableClock clock = new MovableClock();
    private final ConfigRegistry config = mock(ConfigRegistry.class);
    private final ScopeContext scope = SystemScope.own(UUID.randomUUID(), null);
    private final SyncRateLimiter limiter = new SyncRateLimiter(new SyncSettings(config), clock);

    SyncRateLimiterTest() {
        limits(3, 10_000, 5);
    }

    @Test
    void aDeviceSendsItsBatchesPerMinuteAndIsThenToldHowLongToWait() {
        for (int i = 0; i < 3; i++) {
            limiter.admit(scope, TILL_A, 100).close();
        }

        ProblemException refused = refusal(() -> limiter.admit(scope, TILL_A, 100));

        assertThat(refused.messageId()).isEqualTo("sync.rate_limited");
        // Three a minute: the next token is twenty seconds away.
        assertThat(refused.parameters()).containsEntry("retry_after", 20L);

        clock.advance(Duration.ofSeconds(20));
        assertThatCode(() -> limiter.admit(scope, TILL_A, 100).close()).doesNotThrowAnyException();
    }

    @Test
    void aDeviceThatSpentItsBytesWaitsForThemWhileAnotherDeviceSendsOn() {
        limits(100, 10_000, 5);
        limiter.admit(scope, TILL_A, 9_000).close();

        ProblemException refused = refusal(() -> limiter.admit(scope, TILL_A, 2_000));

        // A thousand bytes short at ten thousand an hour: 360 seconds.
        assertThat(refused.parameters()).containsEntry("retry_after", 360L);
        assertThatCode(() -> limiter.admit(scope, TILL_B, 9_000).close()).doesNotThrowAnyException();
    }

    @Test
    void theInstanceTakesSoManyBatchesAtOnceAndARefusedBatchCostsTheDeviceNothing() {
        limits(1, 10_000, 1);
        SyncRateLimiter.Permit first = limiter.admit(scope, TILL_A, 100);

        ProblemException busy = refusal(() -> limiter.admit(scope, TILL_B, 100));
        assertThat(busy.parameters()).containsEntry("retry_after", 1L);
        assertThat(limiter.inFlight()).isEqualTo(1);

        first.close();
        first.close(); // closing twice gives one slot back, not two
        assertThat(limiter.inFlight()).isZero();

        // Till B's only batch of the minute was refused for capacity, not spent.
        assertThatCode(() -> limiter.admit(scope, TILL_B, 100).close()).doesNotThrowAnyException();
    }

    @Test
    void aChangedLimitAppliesToTheNextBatch() {
        limits(1, 10_000, 5);
        limiter.admit(scope, TILL_A, 100).close();
        assertThat(refusal(() -> limiter.admit(scope, TILL_A, 100)).messageId()).isEqualTo("sync.rate_limited");

        limits(60, 10_000, 5);
        clock.advance(Duration.ofSeconds(1));

        assertThatCode(() -> limiter.admit(scope, TILL_A, 100).close()).doesNotThrowAnyException();
    }

    /** Wave 2, TWK-25: the heartbeat, the snapshot, the change log and the presign have a bucket too. */
    @Test
    void aDeviceThatSpentItsRequestsOnHeartbeatsIsToldToWaitAndAnotherIsNot() {
        when(config.getInt(eq("sync.rate.requests_per_minute"), any(), anyInt()))
                .thenReturn(30);
        for (int i = 0; i < 30; i++) {
            limiter.admitRequest(scope, TILL_A, false);
        }

        ProblemException refused = refusal(() -> limiter.admitRequest(scope, TILL_A, false));

        assertThat(refused.messageId()).isEqualTo("sync.rate_limited");
        // Thirty a minute: the next one is two seconds away.
        assertThat(refused.parameters()).containsEntry("retry_after", 2L);
        assertThatCode(() -> limiter.admitRequest(scope, TILL_B, false)).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(2));
        assertThatCode(() -> limiter.admitRequest(scope, TILL_A, false)).doesNotThrowAnyException();
    }

    /** A snapshot's answer costs the hourly byte bucket; the next download waits until it refills. */
    @Test
    void aDownloadIsChargedItsBytesAndTheNextWaitsWhileTheBucketIsSpent() {
        when(config.getInt(eq("sync.rate.requests_per_minute"), any(), anyInt()))
                .thenReturn(30);
        limits(100, 10_000, 5);
        limiter.admitRequest(scope, TILL_A, true);
        limiter.chargeBytes(scope, TILL_A, 12_000);

        ProblemException refused = refusal(() -> limiter.admitRequest(scope, TILL_A, true));

        // Two thousand bytes in debt, plus one to start: 2,001 at ten thousand an hour, 721 seconds.
        assertThat(refused.parameters()).containsEntry("retry_after", 721L);
        // A heartbeat sends nothing heavy and is not held by the byte bucket.
        assertThatCode(() -> limiter.admitRequest(scope, TILL_A, false)).doesNotThrowAnyException();
    }

    private void limits(int batchesPerMinute, int bytesPerHour, int concurrent) {
        when(config.getInt(eq("sync.rate.batches_per_minute"), any(), anyInt())).thenReturn(batchesPerMinute);
        when(config.getInt(eq("sync.rate.bytes_per_hour"), any(), anyInt())).thenReturn(bytesPerHour);
        when(config.getInt(eq("sync.ingest.max_concurrent"), any(), anyInt())).thenReturn(concurrent);
    }

    private static ProblemException refusal(Runnable call) {
        try {
            call.run();
        } catch (ProblemException refused) {
            return refused;
        }
        throw new AssertionError("the batch was let in");
    }

    /** A clock the test moves forward by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
