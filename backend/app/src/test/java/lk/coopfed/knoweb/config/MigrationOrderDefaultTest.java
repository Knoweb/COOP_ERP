package lk.coopfed.knoweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * Flyway accepts a migration below the highest applied version only where somebody asked for
 * it. The default is what a deployed database gets, so it must stay strict; the local stack is
 * the one place that asks, because the 19A lanes merge their version ranges in any order
 * (db/migration/kernel/README.md).
 */
class MigrationOrderDefaultTest {

    @Test
    void theApplicationIsStrictUnlessTheEnvironmentAsks() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);

        // Only the file is a source here, so ${MIGRATION_OUT_OF_ORDER:false} falls to its default.
        String value = new PropertySourcesPropertyResolver(sources).getProperty("coop-erp.migration.out-of-order");

        assertThat(value).isEqualTo("false");
    }

    @Test
    void theLocalStackAsks() throws IOException {
        // Relative to backend/app, the working directory of the Gradle test task.
        String compose = Files.readString(Path.of("../../infra/compose/compose.yml"));

        assertThat(compose).contains("MIGRATION_OUT_OF_ORDER: ${MIGRATION_OUT_OF_ORDER:-true}");
    }
}
