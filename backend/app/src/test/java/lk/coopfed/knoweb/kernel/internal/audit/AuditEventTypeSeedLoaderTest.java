package lk.coopfed.knoweb.kernel.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditEventTypeSeedLoaderTest extends PostgresIntegrationTest {

    @Test
    void loadedEventTypesExactlyMatchTheSeedFilesWithNoDuplicates() throws IOException {
        Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath*:seed/*/audit-event-types.yaml");

        int expectedCount = 0;
        for (Resource resource : resources) {
            YamlMapFactoryBean factory = new YamlMapFactoryBean();
            factory.setResources(resource);
            Map<String, Object> root = factory.getObject();
            if (root != null) {
                Object rawEvents = root.get("audit_event_types");
                if (rawEvents instanceof List<?> events) {
                    expectedCount += events.size();
                }
            }
        }

        JdbcTemplate db = superuserJdbc();
        Integer actualCount = db.queryForObject("select count(*) from kernel.audit_event_type", Integer.class);

        assertThat(actualCount)
                .isEqualTo(expectedCount)
                .withFailMessage(
                        "The number of seeded audit event types (%d) does not match the entries in the YAML files (%d). Check for duplicate event_type_code entries across seed files.",
                        actualCount, expectedCount);
    }
}
