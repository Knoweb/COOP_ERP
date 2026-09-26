package lk.coopfed.knoweb.m2catalogue.internal.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M2 seed loader: the done criterion of M2-01, "migrations and seeds apply". The start of
 * the test context ran it once already, as the test Federation ({@code coop-erp.system.entity-id}
 * of {@code PostgresIntegrationTest}), so the units, the tags and the tax rows are in place;
 * the tax cases remove the tax rows first and run a loader of their own that names a Federation
 * of its own. The counts are the rows of the three YAML files under seed/m2catalogue; change
 * them together with the files.
 */
class M2SeedLoaderTest extends PostgresIntegrationTest {

    private static final int UNITS = 10;
    private static final int GOVERNED_TAGS = 8;
    private static final int TAX_CATEGORIES = 3;
    private static final int TAX_RATES = 3;

    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-0000000003f1");

    @Autowired
    private M2SeedLoader startUpLoader;

    @Autowired
    private ObjectMapper mapper;

    @Value("${coop-erp.migration.url}")
    private String url;

    @Value("${coop-erp.migration.user}")
    private String user;

    @Value("${coop-erp.migration.password}")
    private String password;

    @BeforeEach
    @AfterEach
    void removeTheTaxRows() {
        JdbcTemplate db = superuserJdbc();
        db.update("delete from catalogue.tax_rate where tax_category_id in"
                + " (select tax_category_id from catalogue.tax_category where code in ('STD', 'ZERO', 'EXEMPT'))");
        db.update("delete from catalogue.tax_category where code in ('STD', 'ZERO', 'EXEMPT')");
    }

    @Test
    void theStartUpLoadPutTheUnitsAndTheGovernedTagsInPlace() {
        JdbcTemplate db = superuserJdbc();

        assertThat(count(db, "catalogue.uom")).isEqualTo(UNITS);
        assertThat(count(db, "catalogue.uom where is_weight")).isEqualTo(2); // KG and G
        assertThat(db.queryForObject("select name_ta from catalogue.uom where uom_code = 'KG'", String.class))
                .isEqualTo("கிலோகிராம்");
        assertThat(count(db, "catalogue.tag where governed and owner_entity_id is null"))
                .isEqualTo(GOVERNED_TAGS);
        assertThat(db.queryForObject("select name_si from catalogue.tag where tag_code = 'core-range'", String.class))
                .isEqualTo("මූලික පරාසය");
    }

    @Test
    void withoutAFederationTheLoaderRefusesToStart() {
        // Review of 26 September: an instance that cannot seed the tax categories does not
        // start, instead of skipping them and failing on the first SKU.
        assertThatThrownBy(() -> new M2SeedLoader(mapper, url, user, password, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COOP_ERP_SYSTEM_ENTITY_ID");
        assertThatThrownBy(() -> new M2SeedLoader(mapper, url, user, password, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theStartUpLoaderSeedsTheTaxRowsAsTheTestFederation() {
        assertThat(startUpLoader.loadSeeds()).isEqualTo(TAX_CATEGORIES + TAX_RATES);
        assertThat(count(superuserJdbc(), "catalogue.tax_category where owner_entity_id = '" + TEST_FEDERATION + "'"))
                .isEqualTo(TAX_CATEGORIES);
    }

    @Test
    void theTaxCategoriesAndRatesAreSeededAsTheFederationsOwn() {
        JdbcTemplate db = superuserJdbc();

        int inserted = loaderOfTheFederation().loadSeeds();

        assertThat(inserted).isEqualTo(TAX_CATEGORIES + TAX_RATES);
        assertThat(count(db, "catalogue.tax_category where owner_entity_id = '" + FEDERATION + "'"))
                .isEqualTo(TAX_CATEGORIES);
        assertThat(db.queryForObject(
                        """
                        select r.rate_percent from catalogue.tax_rate r
                          join catalogue.tax_category c on c.tax_category_id = r.tax_category_id
                         where c.code = 'STD' and r.effective_from = date '2024-01-01' and r.effective_to is null
                        """,
                        BigDecimal.class))
                .isEqualByComparingTo("18.00");
        assertThat(count(db, "catalogue.tax_rate where owner_entity_id = '" + FEDERATION + "'"))
                .isEqualTo(TAX_RATES);
    }

    @Test
    void aSecondRunInsertsNothingAndLeavesEditedRowsAlone() {
        JdbcTemplate db = superuserJdbc();
        M2SeedLoader loader = loaderOfTheFederation();
        loader.loadSeeds();
        db.update("update catalogue.uom set name_en = 'Edited' where uom_code = 'EA'");
        db.update("update catalogue.tag set name_en = 'Edited' where tag_code = 'rice'");
        // A rate published since the seed: the seeded STD row is closed and a new one follows.
        db.update(
                """
                update catalogue.tax_rate set effective_to = date '2026-12-31'
                 where tax_category_id = (select tax_category_id from catalogue.tax_category where code = 'STD')
                """);
        db.update(
                """
                insert into catalogue.tax_rate (tax_category_id, rate_percent, effective_from, owner_entity_id)
                select tax_category_id, 20.00, date '2027-01-01', owner_entity_id
                  from catalogue.tax_category where code = 'STD'
                """);

        try {
            assertThat(loader.loadSeeds()).isZero();

            assertThat(db.queryForObject("select name_en from catalogue.uom where uom_code = 'EA'", String.class))
                    .isEqualTo("Edited");
            assertThat(db.queryForObject("select name_en from catalogue.tag where tag_code = 'rice'", String.class))
                    .isEqualTo("Edited");
            assertThat(count(db, "catalogue.tax_rate where owner_entity_id = '" + FEDERATION + "'"))
                    .as("the seeded rate overlaps the rates in the table, so it is not inserted again")
                    .isEqualTo(TAX_RATES + 1);
        } finally {
            db.update("update catalogue.uom set name_en = 'Each' where uom_code = 'EA'");
            db.update("update catalogue.tag set name_en = 'Rice' where tag_code = 'rice'");
        }
    }

    private M2SeedLoader loaderOfTheFederation() {
        return new M2SeedLoader(mapper, url, user, password, FEDERATION.toString());
    }

    private static int count(JdbcTemplate db, String from) {
        return db.queryForObject("select count(*) from " + from, Integer.class);
    }
}
