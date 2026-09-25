package lk.coopfed.knoweb.kernel.internal.job;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds every {@link ScheduledJob} method as its bean is created and, once every bean exists,
 * upserts the catalogue row of each ({@code kernel.scheduled_job}). The code owns the
 * schedule and the limits, so those follow the code on every start; {@code enabled} is the
 * console's switch and is never written here (19A section 12: "never flips enabled").
 *
 * <p>Every role upserts, because the catalogue must show what the deployed code carries even
 * on an instance that does not run jobs; only the worker role schedules them ({@link JobScheduler}).
 */
@Component
public class JobRegistry implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    private static final String PACKAGE_ROOT = "lk.coopfed.knoweb.";

    private final Map<String, JobDefinition> definitions = new ConcurrentHashMap<>();

    private final Environment environment;

    public JobRegistry(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        register(bean);
        return bean;
    }

    void register(Object bean) {
        Class<?> type = AopUtils.getTargetClass(bean);

        for (Method method : type.getDeclaredMethods()) {
            ScheduledJob annotation = method.getAnnotation(ScheduledJob.class);

            if (annotation == null) {
                continue;
            }

            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
            invocable.setAccessible(true);

            // A cron may be a placeholder ("${coop-erp.x.cron:0 5 * * * *}"), resolved like any setting.
            JobDefinition definition = JobDefinition.of(
                    annotation, environment.resolvePlaceholders(annotation.cron()), bean, invocable, moduleOf(type));

            JobDefinition existing = definitions.putIfAbsent(definition.name(), definition);

            // The same bean under a second name (a configuration class that is also a bean) is one job.
            if (existing != null && existing.bean() == bean && existing.method().equals(invocable)) {
                continue;
            }

            if (existing != null) {
                throw new IllegalStateException("Two @ScheduledJob methods share the name " + definition.name() + ": "
                        + existing.method() + " and " + method);
            }
        }
    }

    Optional<JobDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    Collection<JobDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /** Upserts every definition into the catalogue, leaving {@code enabled} as it is. */
    void upsertCatalogue(JdbcTemplate jdbc) {
        for (JobDefinition job : definitions.values()) {
            jdbc.update(
                    """
                    insert into kernel.scheduled_job (
                        name, module, cron, continuous, fixed_delay, lock_timeout, max_runtime, critical
                    ) values (?, ?, ?, ?, cast(? as interval), cast(? as interval), cast(? as interval), ?)
                    on conflict (name) do update set
                        module = excluded.module,
                        cron = excluded.cron,
                        continuous = excluded.continuous,
                        fixed_delay = excluded.fixed_delay,
                        lock_timeout = excluded.lock_timeout,
                        max_runtime = excluded.max_runtime,
                        critical = excluded.critical,
                        updated_at = now()
                    """,
                    ps -> {
                        ps.setString(1, job.name());
                        ps.setString(2, job.module());
                        ps.setString(3, job.cron());
                        ps.setBoolean(4, job.continuous());
                        // PostgreSQL reads an ISO 8601 duration (PT10M) as an interval.
                        ps.setString(
                                5,
                                job.fixedDelay() == null
                                        ? null
                                        : job.fixedDelay().toString());
                        ps.setString(6, job.lockTimeout().toString());
                        ps.setString(7, job.maxRuntime().toString());
                        ps.setBoolean(8, job.critical());
                    });
        }

        log.info("Registered {} scheduled job(s) in kernel.scheduled_job", definitions.size());
    }

    /** The module a bean belongs to, from its package: lk.coopfed.knoweb.m3pricing... is m3pricing. */
    static String moduleOf(Class<?> type) {
        String name = type.getName();

        if (!name.startsWith(PACKAGE_ROOT)) {
            return "external";
        }

        String rest = name.substring(PACKAGE_ROOT.length());
        int dot = rest.indexOf('.');

        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
