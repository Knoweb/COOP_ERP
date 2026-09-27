package lk.coopfed.knoweb.m1party.internal.security.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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

    // The catalogue of M1 (22 after CR-21A-1 retired the four coarse manage codes and added
    // gov.audit.review), bil.creditlimit.change of M4, the two of M3 and the 13 of M2 (22A
    // section 3.1 plus cat.sku.view and cat.tag.govern, CR-22A-3): the number of `- code:` lines
    // in permissions.yaml. Plus the 18 of M5 (25A section 3.1 and inv.stock.view).
    private static final int PERMISSIONS = 56;
    private static final int ROLE_TEMPLATES = 4;
    private static final int SOD_PAIRS = 2;

    /** The codes CR-21A-1 item 1 retired; m1security V0014 removes them from older databases. */
    private static final List<String> RETIRED =
            List.of("gov.entity.manage", "prt.relationship.manage", "prt.location.manage", "sys.device.manage");

    /**
     * M1 codes that no operation of m1party.yaml carries, each for a reason: gov.audit.review is
     * 21A section 3.3's, held for the audit review of doc 19 and the self-review pair of
     * sod-pairs.yaml, and no M1 operation reads the audit trail.
     */
    private static final Set<String> M1_CODES_WITHOUT_OPERATION = Set.of("gov.audit.review");

    private static final Pattern PERMISSION_CODE = Pattern.compile("^  - code: \"([^\"]+)\"$");
    private static final Pattern TEMPLATE_ID = Pattern.compile("^  - role_id: \"([^\"]+)\"$");
    private static final Pattern PAIR_ID = Pattern.compile("^  - id: \"([^\"]+)\"$");
    private static final Pattern SLICE_PERMISSION = Pattern.compile("^\\s+x-permission: (\\S+)$");

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

    @Test
    void theM1CatalogueIsExactlyWhatTheSliceAsksForAndNoRetiredCodeIsLeft() {
        // CR-21A-1 item 1: one code per verb, and no code that nothing asks for. A new M1 code
        // without an operation, or an operation naming a code the catalogue lacks, fails here.
        Set<String> catalogue = new TreeSet<>(idsIn("permissions.yaml", PERMISSION_CODE));
        Set<String> slice = new TreeSet<>(matchesIn("openapi/m1party.yaml", SLICE_PERMISSION));
        assertThat(catalogue)
                .as("every x-permission of m1party.yaml is in the catalogue")
                .containsAll(slice);

        Set<String> m1Codes = new TreeSet<>();
        for (String code : catalogue) {
            if (code.startsWith("gov.") || code.startsWith("prt.") || code.startsWith("sys.")) {
                m1Codes.add(code);
            }
        }
        Set<String> unused = new TreeSet<>(m1Codes);
        unused.removeAll(slice);
        assertThat(unused).as("M1 codes no operation carries").isEqualTo(M1_CODES_WITHOUT_OPERATION);
        assertThat(catalogue).doesNotContainAnyElementsOf(RETIRED);

        JdbcTemplate db = superuserJdbc();
        assertThat(db.queryForObject(
                        "SELECT COUNT(*) FROM security.permission WHERE permission_code = ANY (?)",
                        Integer.class,
                        (Object) RETIRED.toArray(String[]::new)))
                .isZero();
    }

    @Test
    void theRetirementMigrationRemovesARetiredCodeWithItsGrantsAndPairsAndBumpsTheVersion() throws IOException {
        // An older database loaded the four codes before they were retired: put one back the way
        // the loader wrote it (catalogue row, a template's grant, a federation pair), then run
        // V0014 again. It is idempotent, so running it on the shared database is safe.
        JdbcTemplate db = superuserJdbc();
        String template = idsIn("role-templates.yaml", TEMPLATE_ID).get(2); // Entity Administrator
        db.update("INSERT INTO security.permission (permission_code, module, description_en, scope)"
                + " VALUES ('prt.location.manage', 'm1party', 'Register and update locations', 'ENTITY')");
        db.update(
                "INSERT INTO security.role_permission (role_id, permission_code) VALUES (CAST(? AS uuid), 'prt.location.manage')",
                template);
        db.update("INSERT INTO security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)"
                + " VALUES ('01998f2a-3c41-7d5e-8b62-4a1f0c9e7d99', 'gov.external.grant', 'prt.location.manage',"
                + " 'INSTANCE', NULL)");
        int versionBefore = db.queryForObject(
                "SELECT COALESCE(MAX(rv), 0) FROM security.permission_catalogue_version", Integer.class);

        db.execute(new ClassPathResource("db/migration/m1security/V0014__retire_coarse_permission_codes.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(db.queryForObject(
                        "SELECT COUNT(*) FROM security.permission WHERE permission_code = 'prt.location.manage'",
                        Integer.class))
                .isZero();
        assertThat(db.queryForObject(
                        "SELECT COUNT(*) FROM security.role_permission WHERE permission_code = 'prt.location.manage'",
                        Integer.class))
                .isZero();
        assertThat(db.queryForObject(
                        "SELECT COUNT(*) FROM security.sod_pair WHERE sod_pair_id = '01998f2a-3c41-7d5e-8b62-4a1f0c9e7d99'",
                        Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT MAX(rv) FROM security.permission_catalogue_version", Integer.class))
                .as("cached permission sets are invalidated")
                .isEqualTo(versionBefore + 1);
        assertThat(seededPermissionCount(db))
                .as("the current catalogue is untouched")
                .isEqualTo(PERMISSIONS);
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
        return matchesIn("seed/m1party/" + file, line);
    }

    private static List<String> matchesIn(String classpathResource, Pattern line) {
        List<String> ids = new ArrayList<>();
        try {
            String text = new ClassPathResource(classpathResource).getContentAsString(StandardCharsets.UTF_8);
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
