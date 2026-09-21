package lk.coopfed.knoweb.config;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Value("${coop-erp.migration.url}")
    private String url;

    @Value("${coop-erp.migration.user}")
    private String user;

    @Value("${coop-erp.migration.password}")
    private String password;

    @PostConstruct
    public void migrate() {
        log.info("Starting manual Flyway migrations for all modules");

        // Kernel must run first.
        migrateModule(
                "kernel",
                "classpath:db/migration/kernel",
                new String[] { "kernel" },
                "kernel");

        Map<String, ModuleInfo> modules = new LinkedHashMap<>();

        modules.put(
                "hello",
                new ModuleInfo(
                        "classpath:db/migration/hello",
                        new String[] { "hello" },
                        "hello"));

        // M1 is one Spring Modulith module, but it owns two independent
        // Flyway migration streams/schemas.
        modules.put(
                "m1party",
                new ModuleInfo(
                        "classpath:db/migration/m1party",
                        new String[] { "party" },
                        "party"));

        modules.put(
                "m1security",
                new ModuleInfo(
                        "classpath:db/migration/m1security",
                        new String[] { "security" },
                        "security"));

        modules.put(
                "m2catalogue",
                new ModuleInfo(
                        "classpath:db/migration/m2catalogue",
                        new String[] { "catalogue" },
                        "catalogue"));

        modules.put(
                "m3pricing",
                new ModuleInfo(
                        "classpath:db/migration/m3pricing",
                        new String[] { "pricing" },
                        "pricing"));

        modules.put(
                "m4trading",
                new ModuleInfo(
                        "classpath:db/migration/m4trading",
                        new String[] { "trading" },
                        "trading"));

        modules.put(
                "m5inventory",
                new ModuleInfo(
                        "classpath:db/migration/m5inventory",
                        new String[] { "inventory" },
                        "inventory"));

        modules.put(
                "m6pos",
                new ModuleInfo(
                        "classpath:db/migration/m6pos",
                        new String[] { "pos" },
                        "pos"));

        modules.put(
                "m7customers",
                new ModuleInfo(
                        "classpath:db/migration/m7customers",
                        new String[] { "customers" },
                        "customers"));

        modules.put(
                "m8reporting",
                new ModuleInfo(
                        "classpath:db/migration/m8reporting",
                        new String[] { "reporting" },
                        "reporting"));

        modules.put(
                "m9integration",
                new ModuleInfo(
                        "classpath:db/migration/m9integration",
                        new String[] { "integration" },
                        "integration"));

        for (Map.Entry<String, ModuleInfo> entry : modules.entrySet()) {
            ModuleInfo info = entry.getValue();

            migrateModule(
                    entry.getKey(),
                    info.location,
                    info.schemas,
                    info.historySchema);
        }

        log.info("Successfully completed all Flyway migrations");
    }

    private void migrateModule(
            String name,
            String location,
            String[] schemas,
            String historySchema) {

        log.info(
                "Migrating module: {} (Location: {}, Schemas: {})",
                name,
                location,
                String.join(", ", schemas));

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(location)
                .schemas(schemas)
                .defaultSchema(historySchema)
                .outOfOrder(false)
                .validateOnMigrate(true)
                .load();

        flyway.migrate();
    }

    private static class ModuleInfo {

        String location;
        String[] schemas;
        String historySchema;

        ModuleInfo(
                String location,
                String[] schemas,
                String historySchema) {

            this.location = location;
            this.schemas = schemas;
            this.historySchema = historySchema;
        }
    }
}