package lk.coopfed.knoweb.kernel.internal.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The job runner against PostgreSQL (19A section 12, "Tests"): two instances, one run per
 * schedule; timeout handling; a critical job retried once; the catalogue switch honoured
 * and never flipped by the code. The system entity is set, so a timed-out run leaves its
 * ALERT record.
 */
@Import(JobRunnerPostgresIntegrationTest.TestJobs.class)
class JobRunnerPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SYSTEM_ENTITY = UUID.fromString("0190e000-0000-7000-8000-000000000001");

    @DynamicPropertySource
    static void systemEntity(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", SYSTEM_ENTITY::toString);
    }

    @Autowired
    JobRunner runner;

    @Autowired
    JobRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestJobs jobs;

    @BeforeEach
    void cleanRuns() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from kernel.job_run");
        // Never delete kernel.shedlock rows here: ShedLock remembers which rows it has created and
        // updates them from then on; a row deleted behind its back makes every later lock fail.
        // A lock outlives its run (jobs.lock.at_least_for, and until lockTimeout after a
        // timeout), so the rows are expired instead.
        expireLocks();
        admin.execute("update kernel.scheduled_job set enabled = true");
        admin.execute("truncate table kernel.audit_event");
        jobs.reset();
        registry.upsertCatalogue(jdbc);
    }

    @Test
    void theCodeRegistersItsJobsAndNeverFlipsTheSwitch() {
        List<Map<String, Object>> rows = superuserJdbc()
                .queryForList("select name, module, cron, continuous, critical, enabled from kernel.scheduled_job"
                        + " where name like 'test-%' order by name");

        assertThat(rows)
                .extracting(row -> row.get("name"))
                .containsExactly("test-counts", "test-critical", "test-fails", "test-slow");
        assertThat(rows.get(0).get("module")).isEqualTo("kernel");
        assertThat(rows.get(1).get("critical")).isEqualTo(true);

        // The console switches a job off; the next start of the code leaves it off.
        superuserJdbc().update("update kernel.scheduled_job set enabled = false where name = 'test-counts'");
        registry.upsertCatalogue(jdbc);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select enabled from kernel.scheduled_job where name = 'test-counts'", Boolean.class))
                .isFalse();

        assertThat(runner.run("test-counts")).isEqualTo(JobRunner.Outcome.SKIPPED_DISABLED);
        assertThat(jobs.counts.get()).isZero();

        // The kernel's own jobs are in the catalogue too.
        assertThat(superuserJdbc().queryForList("select name from kernel.scheduled_job order by name", String.class))
                .contains("day-close-cutoff", "gap-check", "idempotency-expiry", "partition-maintenance");
    }

    @Test
    void aRunIsRecordedWithWhatItProcessed() {
        assertThat(runner.run("test-counts")).isEqualTo(JobRunner.Outcome.SUCCESS);

        Map<String, Object> run = superuserJdbc()
                .queryForMap("select outcome, items_processed, error, finished_at from kernel.job_run"
                        + " where name = 'test-counts'");
        assertThat(run.get("outcome")).isEqualTo("SUCCESS");
        assertThat(run.get("items_processed")).isEqualTo(7);
        assertThat(run.get("error")).isNull();
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(runner.run("unknown-job")).isEqualTo(JobRunner.Outcome.UNKNOWN_JOB);
    }

    @Test
    void twoInstancesMakeOneRunPerSchedule() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JobRunner.Outcome> first = pool.submit(() -> runner.run("test-slow"));
            Future<JobRunner.Outcome> second = pool.submit(() -> runner.run("test-slow"));

            List<JobRunner.Outcome> outcomes =
                    List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder(JobRunner.Outcome.SUCCESS, JobRunner.Outcome.SKIPPED_LOCKED);
        } finally {
            pool.shutdownNow();
        }

        assertThat(superuserJdbc()
                        .queryForObject("select count(*) from kernel.job_run where name = 'test-slow'", Long.class))
                .isEqualTo(1L);
        assertThat(jobs.slowRuns.get()).isEqualTo(1);

        // The lock is held a little after the run (jobs.lock.at_least_for): a second instance
        // firing the same schedule a moment later skips instead of running it again.
        assertThat(runner.run("test-slow")).isEqualTo(JobRunner.Outcome.SKIPPED_LOCKED);
        assertThat(jobs.slowRuns.get()).isEqualTo(1);

        // Once it has expired, the next schedule runs again.
        expireLocks();
        assertThat(runner.run("test-slow")).isEqualTo(JobRunner.Outcome.SUCCESS);
    }

    @Test
    void aRunPastItsMaximumIsInterruptedMarkedTimeoutAndAlerted() {
        jobs.slowFor = 5_000;

        assertThat(runner.run("test-slow")).isEqualTo(JobRunner.Outcome.TIMEOUT);

        Map<String, Object> run =
                superuserJdbc().queryForMap("select outcome, error from kernel.job_run where name = 'test-slow'");
        assertThat(run.get("outcome")).isEqualTo("TIMEOUT");
        assertThat(String.valueOf(run.get("error"))).contains("max runtime");
        assertThat(jobs.slowInterrupted.get()).isTrue();

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.audit_event where event_type_code = 'JOB_TIMED_OUT'"
                                        + " and owner_entity_id = ?",
                                Long.class,
                                SYSTEM_ENTITY))
                .isEqualTo(1L);

        // The interrupted body may still be inside a blocking call, so the lock is kept until
        // lockTimeout (PT1M here): the next firing skips rather than overlaps it.
        assertThat(runner.run("test-slow")).isEqualTo(JobRunner.Outcome.SKIPPED_LOCKED);
        assertThat(jobs.slowRuns.get()).isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select lock_until > " + DB_NOW + " + interval '30 seconds' from kernel.shedlock"
                                        + " where name = 'job:test-slow'",
                                Boolean.class))
                .isTrue();

        // The row reads as UTC whatever zone the session runs in (kernel/V0057): locked_at is
        // now, not five and a half hours ago.
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select abs(extract(epoch from (locked_at - " + DB_NOW + "))) < 60"
                                        + " from kernel.shedlock where name = 'job:test-slow'",
                                Boolean.class))
                .isTrue();
    }

    /** ShedLock's PostgreSQL statements keep the lock times as naive UTC; the tests read them the same way. */
    private static final String DB_NOW = "timezone('utc', now())";

    /** Ends every lock now, as the passing of lockTimeout would. */
    private static void expireLocks() {
        superuserJdbc().execute("update kernel.shedlock set lock_until = " + DB_NOW + " where lock_until > " + DB_NOW);
    }

    @Test
    void aFailureIsRecordedAndACriticalJobIsRetriedOnce() {
        assertThat(runner.run("test-fails")).isEqualTo(JobRunner.Outcome.FAILED);
        assertThat(jobs.failures.get()).isEqualTo(1);

        assertThat(runner.run("test-critical")).isEqualTo(JobRunner.Outcome.FAILED);
        assertThat(jobs.criticalAttempts.get()).isEqualTo(2);

        List<Map<String, Object>> runs = superuserJdbc()
                .queryForList(
                        "select outcome, error from kernel.job_run where name = 'test-critical' order by started_at");
        assertThat(runs).hasSize(2);
        assertThat(runs).allSatisfy(run -> {
            assertThat(run.get("outcome")).isEqualTo("FAILED");
            assertThat(String.valueOf(run.get("error"))).contains("boom");
        });

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.audit_event where event_type_code = 'JOB_FAILED'",
                                Long.class))
                .isEqualTo(3L);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        final AtomicInteger counts = new AtomicInteger();
        final AtomicInteger slowRuns = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger criticalAttempts = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean slowInterrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile long slowFor = 1_500;

        void reset() {
            counts.set(0);
            slowRuns.set(0);
            failures.set(0);
            criticalAttempts.set(0);
            slowInterrupted.set(false);
            slowFor = 1_500;
        }

        @ScheduledJob(name = "test-counts", cron = "0 0 3 * * *", lockTimeout = "PT2M", maxRuntime = "PT1M")
        public int counts(JobExecution execution) {
            counts.incrementAndGet();
            execution.itemsProcessed(3);
            assertThat(execution.systemScope()).isPresent();
            return 7;
        }

        @ScheduledJob(name = "test-slow", cron = "0 0 3 * * *", lockTimeout = "PT1M", maxRuntime = "PT3S")
        public void slow() throws InterruptedException {
            slowRuns.incrementAndGet();
            CountDownLatch never = new CountDownLatch(1);
            try {
                never.await(slowFor, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                slowInterrupted.set(true);
                throw interrupted;
            }
        }

        @ScheduledJob(name = "test-fails", cron = "0 0 3 * * *", lockTimeout = "PT2M", maxRuntime = "PT1M")
        public void fails() {
            failures.incrementAndGet();
            throw new IllegalStateException("boom");
        }

        @ScheduledJob(
                name = "test-critical",
                cron = "0 0 3 * * *",
                critical = true,
                // A critical job runs twice under one lock: longer than twice the runtime.
                lockTimeout = "PT3M",
                maxRuntime = "PT1M")
        public void critical() {
            criticalAttempts.incrementAndGet();
            throw new IllegalStateException("boom again");
        }
    }
}
