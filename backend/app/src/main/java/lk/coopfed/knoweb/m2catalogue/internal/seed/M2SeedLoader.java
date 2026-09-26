package lk.coopfed.knoweb.m2catalogue.internal.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads M2's reference data from the YAML files under {@code seed/m2catalogue} when the
 * application starts (22A section 3.1): the units of measure, the tax categories with their
 * rates, and the governed tags. A row is inserted when its key is absent and never changed
 * afterwards, so a unit, category or tag the Federation has edited keeps its edit.
 *
 * <p>It connects as the migrator, exactly as {@code M1SeedLoader} does (decided by the
 * architect on 23 September 2026): these tables are reference data, the application role
 * {@code app_rw} may not write them, and the migrator writes them as a member of
 * {@code app_seed}, the group the {@code seed_reference} policies of m2catalogue V0001 admit.
 *
 * <p>The tax categories and rates belong to the Federation, which publishes later rates in its
 * own scope (cat.tax.publish), so a seeded row carries the Federation as its owner:
 * {@code coop-erp.system.entity-id}, the entity the platform acts as (the local stack sets it to
 * the development Federation, the test base class to a test one). Without it the categories
 * cannot be seeded, and every SKU cites a category ({@code sku.tax_category_id NOT NULL}), so an
 * instance without a Federation does not start: the constructor refuses it (review of 26
 * September 2026; until then the tax rows were skipped with a warning and the first
 * registration failed instead).
 */
@Component
public class M2SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(M2SeedLoader.class);

    static final String FEDERATION_REQUIRED =
            "M2 cannot seed the tax categories: coop-erp.system.entity-id (COOP_ERP_SYSTEM_ENTITY_ID),"
                    + " the entity that owns them, is not set";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;
    private final UUID federation;

    private final Resource uomResource = new ClassPathResource("seed/m2catalogue/uom.yaml");
    private final Resource taxResource = new ClassPathResource("seed/m2catalogue/tax.yaml");
    private final Resource tagsResource = new ClassPathResource("seed/m2catalogue/tags.yaml");

    @Autowired
    public M2SeedLoader(
            ObjectMapper mapper,
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password,
            @Value("${coop-erp.system.entity-id:}") String federationEntityId) {
        DriverManagerDataSource migrator = new DriverManagerDataSource(url, user, password);
        this.jdbc = JdbcClient.create(migrator);
        this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(migrator));
        this.mapper = mapper;
        if (federationEntityId == null || federationEntityId.isBlank()) {
            throw new IllegalStateException(FEDERATION_REQUIRED);
        }
        this.federation = UUID.fromString(federationEntityId.strip());
    }

    /**
     * Runs once the application is up. A failure is a failure to start: an instance whose
     * catalogue has no units cannot register an item, so nothing is caught here.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        loadSeeds();
    }

    /** Loads the three files in one transaction; returns the number of rows really inserted. */
    public int loadSeeds() {
        log.info("Loading M2 catalogue seeds as the migrator");
        Integer inserted = transactionTemplate.execute(status -> loadUnits() + loadTags() + loadTax());
        log.info("M2 catalogue seeds loaded, {} row(s) inserted", inserted);
        return inserted == null ? 0 : inserted;
    }

    private int loadUnits() {
        int inserted = 0;
        for (UnitSeed unit : read(uomResource, "uom", UnitSeed.class)) {
            inserted += jdbc.sql(
                            """
                            INSERT INTO catalogue.uom (uom_code, name_en, name_si, name_ta, is_weight)
                            VALUES (:code, :en, :si, :ta, :weight)
                            ON CONFLICT (uom_code) DO NOTHING
                            """)
                    .param("code", unit.code())
                    .param("en", unit.en())
                    .param("si", unit.si())
                    .param("ta", unit.ta())
                    .param("weight", Boolean.TRUE.equals(unit.weight()))
                    .update();
        }
        return inserted;
    }

    private int loadTags() {
        int inserted = 0;
        for (TagSeed tag : read(tagsResource, "tags", TagSeed.class)) {
            // Governed: the Federation's vocabulary, which no entity owns (doc 22 section 3.5).
            inserted += jdbc.sql(
                            """
                            INSERT INTO catalogue.tag (tag_code, name_en, name_si, name_ta, governed, owner_entity_id)
                            VALUES (:code, :en, :si, :ta, true, NULL)
                            ON CONFLICT (tag_code) DO NOTHING
                            """)
                    .param("code", tag.code())
                    .param("en", tag.en())
                    .param("si", tag.si())
                    .param("ta", tag.ta())
                    .update();
        }
        return inserted;
    }

    private int loadTax() {
        UUID owner = federation;
        int inserted = 0;
        for (TaxSeed category : read(taxResource, "tax", TaxSeed.class)) {
            inserted += jdbc.sql(
                            """
                            INSERT INTO catalogue.tax_category (tax_category_id, code, name_en, name_si, name_ta, owner_entity_id)
                            VALUES (:id, :code, :en, :si, :ta, :owner)
                            ON CONFLICT (code) DO NOTHING
                            """)
                    .param("id", Ids.next())
                    .param("code", category.code())
                    .param("en", category.en())
                    .param("si", category.si())
                    .param("ta", category.ta())
                    .param("owner", owner)
                    .update();
            UUID categoryId = jdbc.sql("SELECT tax_category_id FROM catalogue.tax_category WHERE code = :code")
                    .param("code", category.code())
                    .query(UUID.class)
                    .single();
            for (RateSeed rate : category.rates()) {
                // No conflict target: the arbiter is the exclusion constraint, so a rate that
                // overlaps any rate of the category (this one, or one published since) is left out.
                inserted += jdbc.sql(
                                """
                                INSERT INTO catalogue.tax_rate (tax_category_id, rate_percent, effective_from, owner_entity_id)
                                VALUES (:category, :percent, :from, :owner)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("category", categoryId)
                        .param("percent", rate.percent())
                        .param("from", rate.from())
                        .param("owner", owner)
                        .update();
            }
        }
        return inserted;
    }

    private <T> List<T> read(Resource resource, String key, Class<T> type) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(resource);
        Map<String, Object> root = factory.getObject();
        Object rows = root == null ? null : root.get(key);
        if (!(rows instanceof List<?> list)) {
            throw new IllegalStateException(resource.getDescription() + " must hold a list called " + key);
        }
        return list.stream().map(row -> mapper.convertValue(row, type)).toList();
    }

    record UnitSeed(String code, String en, String si, String ta, Boolean weight) {}

    record TagSeed(String code, String en, String si, String ta) {}

    record TaxSeed(String code, String en, String si, String ta, List<RateSeed> rates) {}

    record RateSeed(BigDecimal percent, LocalDate from) {}
}
