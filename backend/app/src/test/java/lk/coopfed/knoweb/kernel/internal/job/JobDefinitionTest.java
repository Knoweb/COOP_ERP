package lk.coopfed.knoweb.kernel.internal.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import org.junit.jupiter.api.Test;

/**
 * The limits a job declares are checked at registration (19A section 12): the lock must
 * outlive the run, and for a critical job, which is retried once under the same lock, two
 * runs (review of 26 Sep: a retry that outlived the lock let a second instance start the job).
 */
class JobDefinitionTest {

    @Test
    void aCriticalJobNeedsALockLongerThanTwoRuns() throws Exception {
        assertThatThrownBy(() -> define("criticalTooShort"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice maxRuntime");

        JobDefinition ok = define("criticalLongEnough");
        assertThat(ok.critical()).isTrue();
        assertThat(ok.lockTimeout()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void anOrdinaryJobNeedsALockLongerThanOneRun() throws Exception {
        assertThat(define("ordinary").lockTimeout()).isEqualTo(Duration.ofMinutes(2));

        assertThatThrownBy(() -> define("ordinaryTooShort"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("longer than maxRuntime");
    }

    private JobDefinition define(String methodName) throws Exception {
        Method method = Samples.class.getDeclaredMethod(methodName);
        ScheduledJob annotation = method.getAnnotation(ScheduledJob.class);
        return JobDefinition.of(annotation, annotation.cron(), new Samples(), method, "kernel");
    }

    static class Samples {

        @ScheduledJob(
                name = "critical-too-short",
                cron = "0 0 3 * * *",
                critical = true,
                lockTimeout = "PT2M",
                maxRuntime = "PT1M")
        public void criticalTooShort() {}

        @ScheduledJob(
                name = "critical-long-enough",
                cron = "0 0 3 * * *",
                critical = true,
                lockTimeout = "PT3M",
                maxRuntime = "PT1M")
        public void criticalLongEnough() {}

        @ScheduledJob(name = "ordinary", cron = "0 0 3 * * *", lockTimeout = "PT2M", maxRuntime = "PT1M")
        public void ordinary() {}

        @ScheduledJob(name = "ordinary-too-short", cron = "0 0 3 * * *", lockTimeout = "PT1M", maxRuntime = "PT1M")
        public void ordinaryTooShort() {}
    }
}
