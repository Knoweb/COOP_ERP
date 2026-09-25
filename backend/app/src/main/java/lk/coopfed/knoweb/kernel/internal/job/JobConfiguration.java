package lk.coopfed.knoweb.kernel.internal.job;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The lock behind every run: a row of {@code kernel.shedlock} per job, taken and released by
 * ShedLock's JDBC provider on the application's own data source (no second store), with the
 * database clock so that two instances with drifting clocks agree on when a lock expires.
 * And the catalogue upsert at start, in every role.
 */
@Configuration(proxyBeanMethods = false)
class JobConfiguration {

    @Bean
    LockProvider jobLockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .withTableName("kernel.shedlock")
                .usingDbTime()
                .build());
    }

    @Bean
    ApplicationRunner jobCatalogueUpsert(JobRegistry registry, JdbcTemplate jdbc) {
        return (ApplicationArguments args) -> registry.upsertCatalogue(jdbc);
    }
}
