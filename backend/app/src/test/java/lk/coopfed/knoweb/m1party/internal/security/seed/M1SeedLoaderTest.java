package lk.coopfed.knoweb.m1party.internal.security.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The seed loader runs on start (the context of this test started it once already) and
 * connects as the migrator. The counts below are the rows of the four YAML files under
 * seed/m1party; change them together with the files.
 *
 * <p>Only the ids the YAML files name are counted, never the whole table: the database is
 * shared by every integration test of the JVM, and a test that leaves one template, one pair
 * or one permission behind (the M1-08 fixture and the kernel's enforcement test both write
 * some) made this test fail far from the cause (review of 26 September 2026).
 */
class M1SeedLoaderTest extends PostgresIntegrationTest {

    // The catalogue of M1 (25 with the locations, grants and relationships) and the 12 of M2 after the M2-02 catalogue
    // read permission: the number of `- code:` lines in permissions.yaml.
    private static final int PERMISSIONS = 40;
    private static final int ROLE_TEMPLATES = 4;
    private static final int SOD_PAIRS = 2;

    private static final Pattern PERMISSION_CODE = Pattern.compile("^  - code: \"([^\"]+)\"$");
    private static final Pattern TEMPLATE_ID = Pattern.compile("^  - role_id: \"([^\"]+)\"$");
    private static final Pattern PAIR_ID = Pattern.compile("^  - id: \"([^\"]+)\"$");

    @Autowired
    private M1SeedLoader seedLoader;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConfigRegistry configRegistry;

    @Test
    void theStartUpLoadPutEveryRowInPlaceExactlyOnce() {
        JdbcTemplate db = superuserJdbc();

        assertThat(seededPermissionCount(db)).isEqualTo(PERMISSIONS);
        assertThat(seededTemplateCount(db)).isEqualTo(ROLE_TEMPLATES);
        assertThat(seededPairCount(db)).isEqualTo(SOD_PAIRS);
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

        assertThat(seededPermissionCount(db)).isEqualTo(PERMISSIONS);
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

    /** The permission rows whose code permissions.yaml names; the YAML must name PERMISSIONS of them. */
    private static int seededPermissionCount(JdbcTemplate db) {
        List<String> codes = idsIn("permissions.yaml", PERMISSION_CODE);
        assertThat(codes).as("`- code:` lines in permissions.yaml").hasSize(PERMISSIONS);
        return db.queryForObject(
                "SELECT COUNT(*) FROM security.permission WHERE permission_code = ANY (?)", Integer.class, (Object)
                        codes.toArray(String[]::new));
    }

    private static int seededTemplateCount(JdbcTemplate db) {
        List<String> ids = idsIn("role-templates.yaml", TEMPLATE_ID);
        assertThat(ids).as("`- role_id:` lines in role-templates.yaml").hasSize(ROLE_TEMPLATES);
        return db.queryForObject(
                "SELECT COUNT(*) FROM security.role WHERE is_template AND role_id = ANY (CAST(? AS uuid[]))",
                Integer.class,
                (Object) ids.toArray(String[]::new));
    }

    private static int seededPairCount(JdbcTemplate db) {
        List<String> ids = idsIn("sod-pairs.yaml", PAIR_ID);
        assertThat(ids).as("`- id:` lines in sod-pairs.yaml").hasSize(SOD_PAIRS);
        return db.queryForObject(
                "SELECT COUNT(*) FROM security.sod_pair WHERE sod_pair_id = ANY (CAST(? AS uuid[]))",
                Integer.class,
                (Object) ids.toArray(String[]::new));
    }

    /** The first group of every line of the seed file that matches, in file order. */
    private static List<String> idsIn(String file, Pattern line) {
        List<String> ids = new ArrayList<>();
        try {
            String text = new ClassPathResource("seed/m1party/" + file).getContentAsString(StandardCharsets.UTF_8);
            for (String each : text.split("\\R")) {
                Matcher matcher = line.matcher(each);
                if (matcher.matches()) {
                    ids.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }
}
