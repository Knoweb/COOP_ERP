package lk.coopfed.knoweb.kernel.internal.audit;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads module-owned audit event catalogue seeds.
 *
 * <p>Every module may contribute a seed/audit-event-types.yaml file. The loader connects
 * using the migration user, which is a member of app_seed, rather than the application role.
 */
@Component
public class AuditEventTypeSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditEventTypeSeedLoader.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    public AuditEventTypeSeedLoader(
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password) {

        DriverManagerDataSource migrator = new DriverManagerDataSource(url, user, password);

        this.jdbc = JdbcClient.create(migrator);
        this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(migrator));
    }

    @Override
    public void run(ApplicationArguments args) {
        loadSeeds();
    }

    public void loadSeeds() {
        Resource[] resources;

        try {
            resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath*:seed/*/audit-event-types.yaml");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot discover module audit-event-types.yaml files", e);
        }

        transactionTemplate.executeWithoutResult(status -> {
            for (Resource resource : resources) {
                load(resource);
            }
        });

        log.info("Loaded audit event catalogue from {} module seed file(s)", resources.length);
    }

    private void load(Resource resource) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(resource);

        Map<String, Object> root = factory.getObject();

        if (root == null) {
            return;
        }

        Object rawEvents = root.get("audit_event_types");

        if (!(rawEvents instanceof List<?> events)) {
            throw new IllegalStateException(resource.getDescription() + " must contain an audit_event_types list");
        }

        for (Object rawEvent : events) {
            if (!(rawEvent instanceof Map<?, ?> event)) {
                throw new IllegalStateException("Invalid audit event entry in " + resource.getDescription());
            }

            String code = required(event, "code", resource);
            String severity = required(event, "severity", resource);
            String description = required(event, "description_en", resource);

            Object offlineValue = event.get("offline_capturable");
            boolean offline = offlineValue != null && Boolean.parseBoolean(offlineValue.toString());

            Object reviewerValue = event.get("reviewer_role_template");
            String reviewer = reviewerValue == null ? null : reviewerValue.toString();

            jdbc.sql(
                            """
                            INSERT INTO kernel.audit_event_type (
                                event_type_code,
                                severity,
                                reviewer_role_template,
                                offline_capturable,
                                description_en
                            )
                            VALUES (
                                :code,
                                :severity,
                                CAST(:reviewer AS uuid),
                                :offline,
                                :description
                            )
                            ON CONFLICT (event_type_code) DO NOTHING
                            """)
                    .param("code", code)
                    .param("severity", severity)
                    .param("reviewer", reviewer)
                    .param("offline", offline)
                    .param("description", description)
                    .update();
        }
    }

    private static String required(Map<?, ?> event, String key, Resource resource) {

        Object value = event.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(resource.getDescription() + " has an audit event without " + key);
        }

        return value.toString();
    }
}
