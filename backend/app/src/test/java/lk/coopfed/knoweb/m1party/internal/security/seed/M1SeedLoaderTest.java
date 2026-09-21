package lk.coopfed.knoweb.m1party.internal.security.seed;

import static org.assertj.core.api.Assertions.assertThat;

import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class M1SeedLoaderTest extends PostgresIntegrationTest {

    @Autowired
    private M1SeedLoader seedLoader;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConfigRegistry configRegistry;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void loadsSeedsIdempotently() {
        // Set scope so the test can read reference data
        jdbc.sql("SET LOCAL app.scope_class = 'FEDERATION_VIEW'").update();

        // Run seed loader manually to ensure it works on demand and is idempotent
        seedLoader.loadSeeds();

        // Verify config
        assertThat(configRegistry.get("trade.federation_direct.enabled", null))
                .isPresent()
                .contains("false");

        // Verify permissions loaded
        int permCount = jdbc.sql("SELECT COUNT(*) FROM security.permission")
                .query(Integer.class)
                .single();
        assertThat(permCount).isGreaterThan(0);

        // Verify role templates
        int templateCount = jdbc.sql("SELECT COUNT(*) FROM security.role WHERE is_template = true")
                .query(Integer.class)
                .single();
        assertThat(templateCount).isGreaterThan(0);

        // Verify SoD pairs
        int sodCount = jdbc.sql("SELECT COUNT(*) FROM security.sod_pair")
                .query(Integer.class)
                .single();
        assertThat(sodCount).isGreaterThan(0);

        // Verify catalogue version
        int version = jdbc.sql("SELECT COALESCE(MAX(rv), 0) FROM security.permission_catalogue_version")
                .query(Integer.class)
                .single();
        assertThat(version).isGreaterThan(0);

        // Second pass: should not throw exception (Idempotent)
        seedLoader.loadSeeds();

        int permCountAfter = jdbc.sql("SELECT COUNT(*) FROM security.permission")
                .query(Integer.class)
                .single();
        assertThat(permCountAfter).isEqualTo(permCount); // Count should remain the same
    }
}
