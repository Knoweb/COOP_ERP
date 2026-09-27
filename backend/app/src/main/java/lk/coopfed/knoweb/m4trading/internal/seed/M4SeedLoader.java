package lk.coopfed.knoweb.m4trading.internal.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
 * Loads the posting map of {@code seed/m4trading/posting-map.yaml} into {@code trading.posting_map}
 * when the application starts (24A section 3.1; doc 24 section 3.9). Account roles, never
 * account numbers: the chart of accounts is mapped when the accounting system is chosen (J-02),
 * and an accountant reviews the roles (doc 10 A-01), so the rows are upserted: a role revisited
 * here reaches every database.
 *
 * <p>It connects as the migrator, as {@code M1SeedLoader} and {@code M2SeedLoader} do: the
 * posting map is reference data, {@code app_rw} may not write it, and the migrator writes it as
 * a member of {@code app_seed}, the group the {@code seed_reference} policy of m4trading V0001
 * admits.
 */
@Component
public class M4SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(M4SeedLoader.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;
    private final Resource postingMap = new ClassPathResource("seed/m4trading/posting-map.yaml");

    @Autowired
    public M4SeedLoader(
            ObjectMapper mapper,
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password) {
        DriverManagerDataSource migrator = new DriverManagerDataSource(url, user, password);
        this.jdbc = JdbcClient.create(migrator);
        this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(migrator));
        this.mapper = mapper;
    }

    /** Runs once the application is up; a failure is a failure to start. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        loadSeeds();
    }

    /** Loads the posting map in one transaction; returns the number of rows written. */
    public int loadSeeds() {
        log.info("Loading M4 trading seeds as the migrator");
        Integer written = transactionTemplate.execute(status -> loadPostingMap());
        log.info("M4 trading seeds loaded, {} posting map row(s)", written);
        return written == null ? 0 : written;
    }

    private int loadPostingMap() {
        int written = 0;
        for (PostingSeed row : readPostingMap()) {
            written += jdbc.sql(
                            """
                            INSERT INTO trading.posting_map
                                (doc_type_code, line_kind, side, debit_role, credit_role, amount_source)
                            VALUES (:doc, :line, :side, :debit, :credit, :amount)
                            ON CONFLICT (doc_type_code, line_kind, side, debit_role, credit_role)
                            DO UPDATE SET amount_source = EXCLUDED.amount_source
                            """)
                    .param("doc", row.doc())
                    .param("line", row.line())
                    .param("side", row.side())
                    .param("debit", row.debit())
                    .param("credit", row.credit())
                    .param("amount", row.amount())
                    .update();
        }
        return written;
    }

    List<PostingSeed> readPostingMap() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(postingMap);
        Map<String, Object> root = factory.getObject();
        Object rows = root == null ? null : root.get("posting_map");
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("seed/m4trading/posting-map.yaml must hold a list called posting_map");
        }
        return list.stream()
                .map(row -> mapper.convertValue(row, PostingSeed.class))
                .toList();
    }

    /** One row of the file: {doc, line, side, debit, credit, amount}, as 24A section 3.1 writes them. */
    record PostingSeed(String doc, String line, String side, String debit, String credit, String amount) {}
}
