package lk.coopfed.knoweb.kernel.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A scheduled job (19A section 12; doc 19 section 9), on a public method of a Spring bean.
 * The kernel registers it in {@code kernel.scheduled_job} at start, runs it on its schedule
 * in the worker role under a lock shared by every instance (one run per schedule, a second
 * instance skips), records every run in {@code kernel.job_run}, interrupts a run that exceeds
 * {@link #maxRuntime()} and marks it TIMEOUT with an ALERT, and retries a {@link #critical()}
 * job once, immediately, when it fails.
 *
 * <p>The method takes no argument or one {@link JobExecution}, and returns nothing or an
 * {@code int}: the number of items it processed, for the run history. It runs with no
 * request behind it and therefore no scope: a job that reads or writes tenant rows sets its
 * own scope through {@link JobExecution#systemScope()} or the scope of the rows it works on.
 *
 * <pre>
 *   &#64;ScheduledJob(name = "rule-expiry", cron = "0 5 0 * * *")
 *   public int expireRules() { ... }
 * </pre>
 *
 * <p>Exactly one of {@link #cron()} (Spring's six-field expression, in the business time
 * zone) and {@link #continuous()} (run again {@link #fixedDelay()} after the last run ended).
 * Durations are ISO 8601 ({@code PT10M}); the lock must outlive the run, so
 * {@code lockTimeout > maxRuntime}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ScheduledJob {

    /** Unique across the application, in kebab case: {@code gap-check}, {@code rule-expiry}. */
    String name();

    String cron() default "";

    boolean continuous() default false;

    String fixedDelay() default "PT10S";

    String lockTimeout() default "PT15M";

    String maxRuntime() default "PT10M";

    /** Retried once immediately on failure (the relay, the gap check); the rest wait for the next schedule. */
    boolean critical() default false;
}
