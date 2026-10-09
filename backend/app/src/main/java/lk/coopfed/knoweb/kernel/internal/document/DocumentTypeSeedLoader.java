package lk.coopfed.knoweb.kernel.internal.document;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the document type registry from {@code seed/kernel/document-types.yaml} on every
 * start, as the migrator (a member of {@code app_seed}): reference data belongs to no tenant
 * and the application user may only read it. Rows are upserted, because 24B says the flags
 * are revisited: a flag changed in the file must reach every database, not only new ones.
 *
 * <p>The kernel's file is the whole registry (doc 18 part C lists the seventeen kinds); a
 * module does not add kinds of its own, it registers a {@code DocumentTypeHandler} for the
 * kinds it owns.
 */
@Component
public class DocumentTypeSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentTypeSeedLoader.class);

    private static final Set<String> SCOPES = Set.of("ENTITY", "LOCATION", "TILL_POSITION");
    private static final Set<String> ROLES = Set.of("BUYER", "SELLER", "HOLDER");
    private static final Pattern EVENT_PREFIX = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    public DocumentTypeSeedLoader(
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password) {

        DriverManagerDataSource migrator = new DriverManagerDataSource(url, user, password);

        this.jdbc = JdbcClient.create(migrator);
        this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(migrator));
    }

    @Override
    public void run(ApplicationArguments args) {
        loadSeeds();
    }

    public void loadSeeds() {
        Resource[] resources;

        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:seed/kernel/document-types.yaml");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot find seed/kernel/document-types.yaml", e);
        }

        if (resources.length == 0) {
            throw new IllegalStateException("seed/kernel/document-types.yaml is missing: no document can be issued");
        }

        int[] rows = {0};

        transactionTemplate.executeWithoutResult(status -> {
            for (Resource resource : resources) {
                rows[0] += load(resource);
            }
        });

        log.info("Loaded {} document type(s) into kernel.document_type", rows[0]);
    }

    private int load(Resource resource) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(resource);

        Map<String, Object> root = factory.getObject();

        if (root == null || !(root.get("document_types") instanceof List<?> types)) {
            throw new IllegalStateException(resource.getDescription() + " must contain a document_types list");
        }

        int count = 0;

        for (Object raw : types) {
            if (!(raw instanceof Map<?, ?> type)) {
                throw new IllegalStateException("Invalid document type entry in " + resource.getDescription());
            }

            String code = required(type, "code", resource);
            String scope = required(type, "series_scope", resource);
            String role = required(type, "issuer_role", resource);

            if (code.length() > 8 || !SCOPES.contains(scope) || !ROLES.contains(role)) {
                throw new IllegalStateException(
                        resource.getDescription() + ": document type " + code + " has an invalid code, scope or role");
            }

            jdbc.sql(
                            """
                            INSERT INTO kernel.document_type (
                                doc_type_code, name_en, name_si, name_ta, series_scope, issuer_role,
                                bilateral, fiscal, offline_issuable, owning_module
                            )
                            VALUES (
                                :code, :nameEn, :nameSi, :nameTa, :scope, :role,
                                :bilateral, :fiscal, :offline, :module
                            )
                            ON CONFLICT (doc_type_code) DO UPDATE SET
                                name_en = EXCLUDED.name_en,
                                name_si = EXCLUDED.name_si,
                                name_ta = EXCLUDED.name_ta,
                                series_scope = EXCLUDED.series_scope,
                                issuer_role = EXCLUDED.issuer_role,
                                bilateral = EXCLUDED.bilateral,
                                fiscal = EXCLUDED.fiscal,
                                offline_issuable = EXCLUDED.offline_issuable,
                                owning_module = EXCLUDED.owning_module
                            """)
                    .param("code", code)
                    .param("nameEn", required(type, "name_en", resource))
                    .param("nameSi", optional(type, "name_si"))
                    .param("nameTa", optional(type, "name_ta"))
                    .param("scope", scope)
                    .param("role", role)
                    .param("bilateral", flag(type, "bilateral"))
                    .param("fiscal", flag(type, "fiscal"))
                    .param("offline", flag(type, "offline_issuable"))
                    .param("module", required(type, "owning_module", resource))
                    .update();

            jdbc.sql("DELETE FROM kernel.document_type_sync_event WHERE doc_type_code = :code")
                    .param("code", code)
                    .update();

            Object syncEventsRaw = type.get("allowed_sync_events");
            if (syncEventsRaw instanceof List<?> syncEvents) {
                Set<String> uniqueEvents = new HashSet<>();
                for (Object event : syncEvents) {
                    if (event != null && !event.toString().isBlank()) {
                        String eventStr = event.toString();
                        if (!EVENT_PREFIX.matcher(eventStr).matches()) {
                            throw new IllegalStateException(resource.getDescription() + ": document type " + code
                                    + " has malformed allowed_sync_events value '" + eventStr + "'");
                        }
                        if (!uniqueEvents.add(eventStr)) {
                            throw new IllegalStateException(resource.getDescription() + ": document type " + code
                                    + " has duplicate allowed_sync_events value '" + eventStr + "'");
                        }
                        jdbc.sql(
                                        "INSERT INTO kernel.document_type_sync_event (doc_type_code, event_prefix) VALUES (:code, :event)")
                                .param("code", code)
                                .param("event", eventStr)
                                .update();
                    }
                }
            }

            count++;
        }

        return count;
    }

    private static String required(Map<?, ?> type, String key, Resource resource) {
        Object value = type.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(resource.getDescription() + " has a document type without " + key);
        }

        return value.toString();
    }

    private static String optional(Map<?, ?> type, String key) {
        Object value = type.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static boolean flag(Map<?, ?> type, String key) {
        Object value = type.get(key);
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
