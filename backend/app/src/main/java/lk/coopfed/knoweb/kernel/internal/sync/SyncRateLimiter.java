package lk.coopfed.knoweb.kernel.internal.sync;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The rate limiting of doc 32 section 9 as 19A section 8 builds it: "token buckets per device
 * (batches per minute, bytes per hour) in memory per instance plus a global concurrency limit".
 * A batch that would go over any of the three is refused with 429 {@code sync.rate_limited} and
 * {@code params.retry_after} in seconds; the till waits that long and sends the same batch again,
 * which is harmless (doc 32 S3).
 *
 * <p>Fairness ("one device cannot starve others"): a device has one batch in flight at most
 * (409 {@code sync.batch_in_flight}) and its own buckets, so however large its backlog it takes no
 * more than its share of the instance's slots, and a refused device is told when to come back
 * rather than queued. After a fleet-wide outage the backlogs drain in turns of a batch per device.
 *
 * <p>The limits are configuration ({@code sync.rate.*}, {@code sync.ingest.max_concurrent}) and
 * are read on every batch, so a change applies at once. The buckets live in this instance's
 * memory: behind a load balancer each instance limits what reaches it. A bucket not used for two
 * hours is forgotten, which is the same as a full one.
 */
@Component
class SyncRateLimiter {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    /** What a device has left: its batches this minute and its bytes this hour. */
    private static final class DeviceBuckets {
        final TokenBucket batches;
        final TokenBucket bytes;

        DeviceBuckets(Instant now) {
            this.batches = new TokenBucket(now);
            this.bytes = new TokenBucket(now);
        }
    }

    /** The slot a batch holds while it is ingested; closing it gives the slot back once. */
    final class Permit implements AutoCloseable {
        private final AtomicBoolean open = new AtomicBoolean(true);

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                inFlight.decrementAndGet();
            }
        }
    }

    private final SyncSettings settings;
    private final Clock clock;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Cache<UUID, DeviceBuckets> devices =
            Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(2)).build();

    SyncRateLimiter(SyncSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Lets one batch in, or refuses it with 429 {@code sync.rate_limited}.
     *
     * @param scope the device's scope, for reading the limits
     * @param bytes the batch's size as sent (compressed when it was)
     * @return the slot, to be closed when the batch is done (try-with-resources)
     */
    Permit admit(ScopeContext scope, UUID deviceId, long bytes) {
        Instant now = clock.instant();
        int perMinute = settings.batchesPerMinute(scope);
        long perHour = settings.bytesPerHour(scope);
        // A batch larger than the whole hour's allowance could never pass; it waits for a full bucket.
        long bytesNeeded = Math.min(Math.max(bytes, 0), perHour);

        DeviceBuckets buckets = devices.get(deviceId, id -> new DeviceBuckets(now));
        synchronized (buckets) {
            // 1. The device's own buckets. Nothing is taken yet: a batch refused further down
            //    must not cost the device its allowance.
            buckets.batches.refill(now, perMinute, MINUTE);
            buckets.bytes.refill(now, perHour, HOUR);
            Duration batchWait = buckets.batches.waitFor(1, perMinute, MINUTE);
            Duration bytesWait = buckets.bytes.waitFor(bytesNeeded, perHour, HOUR);
            Duration wait = batchWait.compareTo(bytesWait) >= 0 ? batchWait : bytesWait;
            if (!wait.isZero()) {
                throw refused(wait);
            }

            // 2. The instance's capacity.
            if (!takeSlot(settings.maxConcurrentBatches(scope))) {
                throw refused(Duration.ofSeconds(1));
            }

            // 3. Admitted: now the tokens are taken.
            buckets.batches.take(1);
            buckets.bytes.take(bytesNeeded);
        }
        return new Permit();
    }

    /** How many batches this instance is ingesting now (for tests and the health view). */
    int inFlight() {
        return inFlight.get();
    }

    /** Forgets every device's buckets (full again): for the tests, which share one instance. */
    void forgetAll() {
        devices.invalidateAll();
    }

    private boolean takeSlot(int max) {
        while (true) {
            int now = inFlight.get();
            if (now >= max) {
                return false;
            }
            if (inFlight.compareAndSet(now, now + 1)) {
                return true;
            }
        }
    }

    private static ProblemException refused(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return new ProblemException("sync.rate_limited", Map.of("retry_after", seconds));
    }

    /**
     * A bucket that fills continuously up to its capacity, capacity per window: a device that sent
     * nothing for a minute has its whole minute's allowance again. The capacity is passed on every
     * call, so a changed limit applies at once.
     */
    static final class TokenBucket {
        private double tokens = Double.NaN; // full on first use
        private Instant last;

        TokenBucket(Instant now) {
            this.last = now;
        }

        void refill(Instant now, double capacity, Duration window) {
            if (Double.isNaN(tokens)) {
                tokens = capacity;
            }
            long elapsedNanos = Math.max(0, Duration.between(last, now).toNanos());
            double perNano = capacity / window.toNanos();
            tokens = Math.min(capacity, tokens + elapsedNanos * perNano);
            last = now.isAfter(last) ? now : last;
        }

        /** Zero when {@code amount} is there now, else how long until it will be. */
        Duration waitFor(double amount, double capacity, Duration window) {
            if (tokens >= amount) {
                return Duration.ZERO;
            }
            double perNano = capacity / window.toNanos();
            return Duration.ofNanos((long) Math.ceil((amount - tokens) / perNano));
        }

        void take(double amount) {
            tokens -= amount;
        }
    }
}
