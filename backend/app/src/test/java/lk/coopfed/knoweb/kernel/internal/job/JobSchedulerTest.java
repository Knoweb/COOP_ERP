package lk.coopfed.knoweb.kernel.internal.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/**
 * The jobs have a pool of their own, one thread per job: a job that blocks for its whole
 * maximum runtime delays no other firing (review of 26 Sep: on the shared single-threaded
 * scheduler a twenty-minute cut-off stalled the outbox relay and every other job).
 */
class JobSchedulerTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger quickRuns = new AtomicInteger();
    private JobScheduler scheduler;

    @AfterEach
    void stop() {
        release.countDown();
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    void aBlockedJobDoesNotHoldTheOthers() throws Exception {
        JobRegistry registry = new JobRegistry(new StandardEnvironment());
        registry.register(new Jobs());

        scheduler = new JobScheduler(
                registry,
                name -> {
                    if (name.equals("test-blocking")) {
                        await(release);
                    } else {
                        quickRuns.incrementAndGet();
                    }
                },
                "Asia/Colombo");

        scheduler.scheduleAll();

        // The blocking job holds its thread; the quick one keeps firing every 50 ms beside it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (quickRuns.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(quickRuns.get()).isGreaterThanOrEqualTo(3);
        assertThat(release.getCount()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class Jobs {

        @ScheduledJob(name = "test-blocking", continuous = true, fixedDelay = "PT0.05S")
        public void blocking() {}

        @ScheduledJob(name = "test-quick", continuous = true, fixedDelay = "PT0.05S")
        public void quick() {}
    }
}
