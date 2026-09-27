package lk.coopfed.knoweb.kernel.internal.job;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Copies {@code coop-erp.system.entity-id} (the Federation, the entity the platform acts as) into
 * {@code kernel.system_identity} on every start, so that SQL can name the Federation:
 * {@code kernel.system_entity()} in a policy or a trigger (kernel V0061; decided 27 September
 * 2026 on the architect's delegation, CR-21A-1 item 2). The property stays the one source; the
 * row is its copy. It writes as the migrator, a member of {@code app_seed}, because the
 * application user may only read the row.
 *
 * <p>Without the property it leaves the row as it is and warns: an instance started without
 * the setting must not erase what the others recorded. A different id replaces the row and is
 * logged, since it re-points every policy that asks for the Federation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SystemEntityRecorder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemEntityRecorder.class);

    private final JdbcClient jdbc;
    private final Optional<UUID> entityId;

    public SystemEntityRecorder(
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password,
            @Value("${coop-erp.system.entity-id:}") String entityId) {

        this.jdbc = JdbcClient.create(new DriverManagerDataSource(url, user, password));
        this.entityId =
                entityId == null || entityId.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(entityId));
    }

    @Override
    public void run(ApplicationArguments args) {
        record();
    }

    /** Writes the configured entity, or leaves the row alone when none is configured. */
    public void record() {
        if (entityId.isEmpty()) {
            log.warn("coop-erp.system.entity-id is not set: kernel.system_identity is left as it is,"
                    + " and a policy that asks for the Federation admits nothing until it is");
            return;
        }

        UUID entity = entityId.get();
        Optional<UUID> before = jdbc.sql("SELECT entity_id FROM kernel.system_identity WHERE singleton")
                .query(UUID.class)
                .optional();

        if (before.isPresent() && before.get().equals(entity)) {
            return;
        }

        jdbc.sql(
                        """
                        INSERT INTO kernel.system_identity (singleton, entity_id, recorded_at)
                        VALUES (true, :entity, now())
                        ON CONFLICT (singleton) DO UPDATE SET entity_id = EXCLUDED.entity_id,
                                                              recorded_at = EXCLUDED.recorded_at
                        """)
                .param("entity", entity)
                .update();

        if (before.isPresent()) {
            log.warn("The system entity changed from {} to {} (coop-erp.system.entity-id)", before.get(), entity);
        } else {
            log.info("Recorded the system entity {} in kernel.system_identity", entity);
        }
    }
}
