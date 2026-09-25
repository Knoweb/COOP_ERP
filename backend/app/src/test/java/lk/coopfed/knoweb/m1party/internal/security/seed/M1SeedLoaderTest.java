package lk.coopfed.knoweb.m1party.internal.security.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The seed loader runs on start (the context of this test started it once already) and
 * connects as the migrator. The counts below are the rows of the four YAML files under
 * seed/m1party; change them together with the files.
 */
class M1SeedLoaderTest extends PostgresIntegrationTest {

    private static final int PERMISSIONS = 27;
    private static final int ROLE_TEMPLATES = 3;
    private static final int SOD_PAIRS = 2;

    @Autowired
    private M1SeedLoader seedLoader;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConfigRegistry configRegistry;

    @Test
    void theStartUpLoadPutEveryRowInPlaceExactlyOnce() {
        JdbcTemplate db = superuserJdbc();

        assertThat(count(db, "security.permission")).isEqualTo(PERMISSIONS);
        assertThat(count(db, "security.role WHERE is_template")).isEqualTo(ROLE_TEMPLATES);
        assertThat(count(db, "security.sod_pair")).isEqualTo(SOD_PAIRS);
        assertThat(configRegistry.get("trade.federation_direct.enabled", null)).contains("false");
    }

    @Test
    void aSecondRunInsertsNothingAndLeavesTheCatalogueVersionAlone() {
        JdbcTemplate db = superuserJdbc();

        db.update(
                "UPDATE security.permission SET description_en = 'Edited text' WHERE permission_code = 'gov.entity.register'");

        int versionBefore = db.queryForObject(
                "SELECT COALESCE(MAX(rv), 0) FROM security.permission_catalogue_version", Integer.class);
        assertThat(versionBefore).isGreaterThan(0); // the start-up load bumped it once

        seedLoader.loadSeeds();

        assertThat(count(db, "security.permission")).isEqualTo(PERMISSIONS);
        assertThat(db.queryForObject("SELECT MAX(rv) FROM security.permission_catalogue_version", Integer.class))
                .as("no row was inserted, so every cached permission set stays valid")
                .isEqualTo(versionBefore);

        String descriptionAfter = db.queryForObject(
                "SELECT description_en FROM security.permission WHERE permission_code = 'gov.entity.register'",
                String.class);
        assertThat(descriptionAfter)
                .as("existing rows remain completely untouched")
                .isEqualTo("Edited text");
    }

    @Test
    void theApplicationRoleCannotWriteTheCatalogue() {
        // V0006 undid the seed grants of V0002: the catalogue is reference data, written by the
        // migrator only. The application connection is app_rw.
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO security.permission (permission_code, module, description_en, scope)"
                                        + " VALUES ('zz.test.write', 'zz', 'must fail', 'LOCATION')")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private static int count(JdbcTemplate db, String from) {
        return db.queryForObject("SELECT COUNT(*) FROM " + from, Integer.class);
    }
}
