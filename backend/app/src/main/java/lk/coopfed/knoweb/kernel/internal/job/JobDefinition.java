package lk.coopfed.knoweb.kernel.internal.job;

import java.lang.reflect.Method;
import java.time.Duration;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;

/** One registered {@link ScheduledJob}: its schedule, its limits, and the method to call. */
record JobDefinition(
        String name,
        String module,
        String cron,
        boolean continuous,
        Duration fixedDelay,
        Duration lockTimeout,
        Duration maxRuntime,
        boolean critical,
        Object bean,
        Method method) {

    static JobDefinition of(ScheduledJob annotation, String cron, Object bean, Method method, String module) {
        String name = annotation.name() == null ? "" : annotation.name().strip();

        if (!name.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalStateException(
                    "@ScheduledJob name must be kebab case (gap-check): '" + name + "' on " + method);
        }

        boolean hasCron = cron != null && !cron.isBlank();

        if (hasCron == annotation.continuous()) {
            throw new IllegalStateException(
                    "@ScheduledJob " + name + " needs exactly one of cron and continuous: " + method);
        }

        Duration lockTimeout = Duration.parse(annotation.lockTimeout());
        Duration maxRuntime = Duration.parse(annotation.maxRuntime());

        if (lockTimeout.compareTo(maxRuntime) <= 0 || maxRuntime.isNegative() || maxRuntime.isZero()) {
            throw new IllegalStateException("@ScheduledJob " + name + ": lockTimeout must be longer than maxRuntime, "
                    + "and maxRuntime positive: " + method);
        }

        // A critical job that fails is run again at once under the same lock (19A section 12), so
        // the lock has to outlive two full runs; otherwise the retry runs past the lock's expiry
        // and a second instance starts the job beside it (review of 26 Sep).
        if (annotation.critical() && lockTimeout.compareTo(maxRuntime.multipliedBy(2)) <= 0) {
            throw new IllegalStateException(
                    "@ScheduledJob " + name + " is critical and is retried once under its lock: "
                            + "lockTimeout must be longer than twice maxRuntime: " + method);
        }

        Class<?>[] parameters = method.getParameterTypes();

        if (parameters.length > 1
                || (parameters.length == 1 && !lk.coopfed.knoweb.kernel.api.JobExecution.class.equals(parameters[0]))) {
            throw new IllegalStateException(
                    "@ScheduledJob " + name + " method takes no argument or one JobExecution: " + method);
        }

        Class<?> returned = method.getReturnType();

        if (!void.class.equals(returned) && !int.class.equals(returned) && !Integer.class.equals(returned)) {
            throw new IllegalStateException("@ScheduledJob " + name + " method returns void or int: " + method);
        }

        return new JobDefinition(
                name,
                module,
                hasCron ? cron.strip() : null,
                annotation.continuous(),
                annotation.continuous() ? Duration.parse(annotation.fixedDelay()) : null,
                lockTimeout,
                maxRuntime,
                annotation.critical(),
                bean,
                method);
    }
}
