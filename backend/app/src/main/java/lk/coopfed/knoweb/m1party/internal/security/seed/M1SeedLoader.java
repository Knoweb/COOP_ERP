package lk.coopfed.knoweb.m1party.internal.security.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ConfigSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class M1SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(M1SeedLoader.class);

    private final JdbcClient jdbc;
    private final ConfigSeeder configSeeder;
    private final TransactionTemplate transactionTemplate;

    @Value("classpath:seed/m1party/permissions.yaml")
    private Resource permissionsResource;

    @Value("classpath:seed/m1party/role-templates.yaml")
    private Resource roleTemplatesResource;

    @Value("classpath:seed/m1party/sod-pairs.yaml")
    private Resource sodPairsResource;

    @Value("classpath:seed/m1party/config.yaml")
    private Resource configResource;

    public M1SeedLoader(JdbcClient jdbc, ConfigSeeder configSeeder, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.configSeeder = configSeeder;
        this.transactionTemplate = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSeeds() {
        log.info("Loading M1 seeds...");
        ObjectMapper mapper = new ObjectMapper();

        transactionTemplate.executeWithoutResult(status -> {
            try {
                jdbc.sql("SET LOCAL app.scope_class = 'SYSTEM_SEED'").update();
                loadConfig(mapper);
                loadPermissions(mapper);
                loadRoleTemplates(mapper);
                loadSodPairs(mapper);
                bumpCatalogueVersion();
                log.info("M1 seeds loaded successfully.");
            } catch (Exception e) {
                log.error("Failed to load M1 seeds", e);
                throw new RuntimeException("Seed loading failed", e);
            }
        });
    }

    private <T> T loadYaml(Resource resource, ObjectMapper mapper, Class<T> type) {
        if (!resource.exists()) return null;
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(resource);
        Map<String, Object> map = factory.getObject();
        if (map == null) return null;
        return mapper.convertValue(map, type);
    }

    private void loadConfig(ObjectMapper mapper) throws IOException {
        SeedRecords.ConfigSeed seed = loadYaml(configResource, mapper, SeedRecords.ConfigSeed.class);
        if (seed == null) return;
        Map<String, String> configs = new HashMap<>();
        for (SeedRecords.ConfigData data : seed.config()) {
            configs.put(data.key(), data.value());
        }
        configSeeder.addDefaults(configs);
        log.info("Loaded {} config seeds into SeedConfigRegistry", configs.size());
    }

    private void loadPermissions(ObjectMapper mapper) throws IOException {
        SeedRecords.PermissionsSeed seed = loadYaml(permissionsResource, mapper, SeedRecords.PermissionsSeed.class);
        if (seed == null) return;

        for (SeedRecords.PermissionData p : seed.permissions()) {
            boolean offline = p.offline_allowed() != null ? p.offline_allowed() : false;
            boolean mfa = p.requires_mfa() != null ? p.requires_mfa() : false;

            jdbc.sql(
                            """
                INSERT INTO security.permission (permission_code, module, description_en, offline_allowed, requires_mfa, scope)
                VALUES (:code, :module, :desc, :offline, :mfa, :scope)
                ON CONFLICT (permission_code) DO NOTHING
            """)
                    .param("code", p.code())
                    .param("module", p.module())
                    .param("desc", p.description_en())
                    .param("offline", offline)
                    .param("mfa", mfa)
                    .param("scope", p.scope())
                    .update();
        }
        log.info("Loaded {} permissions", seed.permissions().size());
    }

    private void loadRoleTemplates(ObjectMapper mapper) throws IOException {
        SeedRecords.RoleTemplatesSeed seed =
                loadYaml(roleTemplatesResource, mapper, SeedRecords.RoleTemplatesSeed.class);
        if (seed == null) return;

        for (SeedRecords.RoleTemplateData t : seed.templates()) {
            jdbc.sql(
                            """
                INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)
                VALUES (:id, NULL, :name, true, :roleClass, 'ACTIVE')
                ON CONFLICT (role_id) DO NOTHING
            """)
                    .param("id", t.role_id())
                    .param("name", t.name_en())
                    .param("roleClass", t.role_class())
                    .update();

            // Insert role permissions
            for (String perm : t.permissions()) {
                jdbc.sql(
                                """
                    INSERT INTO security.role_permission (role_id, permission_code)
                    VALUES (:id, :perm)
                    ON CONFLICT (role_id, permission_code) DO NOTHING
                """)
                        .param("id", t.role_id())
                        .param("perm", perm)
                        .update();
            }
        }
        log.info("Loaded {} role templates", seed.templates().size());
    }

    private void loadSodPairs(ObjectMapper mapper) throws IOException {
        SeedRecords.SodPairsSeed seed = loadYaml(sodPairsResource, mapper, SeedRecords.SodPairsSeed.class);
        if (seed == null) return;

        for (SeedRecords.SodPairData pair : seed.pairs()) {
            // permission_a < permission_b must be true due to check constraint, ensure correct order
            String permA = pair.permission_a();
            String permB = pair.permission_b();
            if (permA.compareTo(permB) > 0) {
                String temp = permA;
                permA = permB;
                permB = temp;
            }

            jdbc.sql(
                            """
                INSERT INTO security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)
                VALUES (:id, :permA, :permB, :mode, NULL)
                ON CONFLICT (sod_pair_id) DO NOTHING
            """)
                    .param("id", pair.id())
                    .param("permA", permA)
                    .param("permB", permB)
                    .param("mode", pair.mode())
                    .update();
        }
        log.info("Loaded {} SoD pairs", seed.pairs().size());
    }

    private void bumpCatalogueVersion() {
        jdbc.sql(
                        """
            INSERT INTO security.permission_catalogue_version (rv, published_at)
            VALUES (
                COALESCE((SELECT MAX(rv) FROM security.permission_catalogue_version), 0) + 1,
                now()
            )
        """)
                .update();
        log.info("Bumped permission catalogue version");
    }
}
