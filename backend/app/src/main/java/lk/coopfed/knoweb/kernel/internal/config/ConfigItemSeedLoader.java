package lk.coopfed.knoweb.kernel.internal.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Upserts the register from {@code seed/kernel/config-items.yaml} on every start, as the
 * migrator (a member of {@code app_seed}): items, never values (19A section 11). Runs before
 * the module seed loaders, which hand their defaults to {@link JdbcConfigRegistry#addDefaults}
 * and reach {@link #registerModuleDefaults} here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ConfigItemSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigItemSeedLoader.class);

    private static final Set<String> TYPES = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DURATION", "JSON");
    private static final Set<String> SCOPES = Set.of("FEDERATION", "ENTITY", "LOCATION");

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public ConfigItemSeedLoader(
            @Value("${coop-erp.migration.url}") String url,
            @Value("${coop-erp.migration.user}") String user,
            @Value("${coop-erp.migration.password}") String password,
            ObjectMapper json) {
        DriverManagerDataSource migrator = new DriverManagerDataSource(url, user, password);
        this.jdbc = JdbcClient.create(migrator);
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(migrator));
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadSeeds();
    }

    public void loadSeeds() {
        Resource[] resources;
        try {
            resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath*:seed/kernel/config-items.yaml");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot find seed/kernel/config-items.yaml", e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("seed/kernel/config-items.yaml is missing: nothing can be configured");
        }

        int[] count = {0};
        transaction.executeWithoutResult(status -> {
            for (Resource resource : resources) {
                count[0] += load(resource);
            }
        });
        log.info("Registered {} configuration item(s) in kernel.config_item", count[0]);
    }

    /** A module's key/value defaults: registered items get the default, unknown keys become string items. */
    void registerModuleDefaults(Map<String, String> defaults) {
        transaction.executeWithoutResult(status -> defaults.forEach((key, value) -> {
            Integer known = jdbc.sql("select count(*) from kernel.config_item where key = :key")
                    .param("key", key)
                    .query(Integer.class)
                    .single();

            if (known > 0) {
                ConfigItem item = jdbc.sql(ConfigItem.SELECT + " where key = :key")
                        .param("key", key)
                        .query(ConfigItem.mapper(json))
                        .single();
                JsonNode parsed = ConfigValues.parse(item, value, json);
                jdbc.sql("update kernel.config_item set default_value = cast(:value as jsonb) where key = :key")
                        .param("value", parsed.toString())
                        .param("key", key)
                        .update();
            } else {
                String module = key.contains(".") ? key.substring(0, key.indexOf('.')) : "kernel";
                jdbc.sql(
                                """
                                insert into kernel.config_item (
                                    key, value_type, schema, description_en, default_value, scope_kind,
                                    change_permission, module
                                ) values (:key, 'STRING', '{}'::jsonb, :description, cast(:value as jsonb), 'ENTITY',
                                          'sys.config.manage', :module)
                                """)
                        .param("key", key)
                        .param("description", "Setting " + key + " of module " + module)
                        .param("value", json.getNodeFactory().textNode(value).toString())
                        .param("module", module)
                        .update();
            }
        }));
    }

    private int load(Resource resource) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(resource);
        Map<String, Object> root = factory.getObject();

        if (root == null || !(root.get("config_items") instanceof List<?> items)) {
            throw new IllegalStateException(resource.getDescription() + " must contain a config_items list");
        }

        int count = 0;
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) {
                throw new IllegalStateException("Invalid configuration item in " + resource.getDescription());
            }
            String key = required(item, "key", resource);
            String type = required(item, "value_type", resource);
            String scope = required(item, "scope_kind", resource);
            if (!TYPES.contains(type) || !SCOPES.contains(scope) || !item.containsKey("default")) {
                throw new IllegalStateException(
                        resource.getDescription() + ": item " + key + " has an invalid type, scope or no default");
            }
            JsonNode schema = json.valueToTree(item.get("schema") == null ? Map.of() : item.get("schema"));
            JsonNode defaultValue = json.valueToTree(item.get("default"));
            if ("DURATION".equals(type) || "STRING".equals(type)) {
                defaultValue = json.getNodeFactory().textNode(String.valueOf(item.get("default")));
            }

            jdbc.sql(
                            """
                            insert into kernel.config_item (
                                key, value_type, schema, description_en, description_si, description_ta,
                                default_value, scope_kind, change_permission, sensitive, till_visible, module
                            ) values (
                                :key, :type, cast(:schema as jsonb), :en, :si, :ta,
                                cast(:default as jsonb), :scope, :permission, :sensitive, :tillVisible, :module
                            )
                            on conflict (key) do update set
                                value_type = excluded.value_type,
                                schema = excluded.schema,
                                description_en = excluded.description_en,
                                description_si = excluded.description_si,
                                description_ta = excluded.description_ta,
                                default_value = excluded.default_value,
                                scope_kind = excluded.scope_kind,
                                change_permission = excluded.change_permission,
                                sensitive = excluded.sensitive,
                                till_visible = excluded.till_visible,
                                module = excluded.module
                            """)
                    .param("key", key)
                    .param("type", type)
                    .param("schema", schema.toString())
                    .param("en", required(item, "description_en", resource))
                    .param("si", optional(item, "description_si"))
                    .param("ta", optional(item, "description_ta"))
                    .param("default", defaultValue.toString())
                    .param("scope", scope)
                    .param("permission", required(item, "change_permission", resource))
                    .param("sensitive", flag(item, "sensitive"))
                    .param("tillVisible", flag(item, "till_visible"))
                    .param(
                            "module",
                            item.get("module") == null
                                    ? "kernel"
                                    : item.get("module").toString())
                    .update();
            count++;
        }
        return count;
    }

    private static String required(Map<?, ?> item, String key, Resource resource) {
        Object value = item.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(resource.getDescription() + " has a configuration item without " + key);
        }
        return value.toString();
    }

    private static String optional(Map<?, ?> item, String key) {
        Object value = item.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static boolean flag(Map<?, ?> item, String key) {
        Object value = item.get(key);
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
