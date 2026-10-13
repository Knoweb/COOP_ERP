package lk.coopfed.knoweb.kernel.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor.Shop;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.sync.SnapshotBuilder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL integration tests for {@link ConfigSnapshotContributor} and {@link ConfigChangeLogFanOut}
 * (doc 32 section 5; K-08, K-11).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Full snapshots deliver all applicable {@code till_visible} configuration items with resolved defaults.
 *   <li>Non-visible configuration items are excluded from table {@code "config"}.
 *   <li>Location &gt; Entity &gt; Federation &gt; Default precedence is strictly honoured.
 *   <li>Entity-level khata cap changes reach enrolled tills of that entity through the next delta.
 *   <li>Cross-society isolation: a society's override does not reach sibling societies.
 *   <li>Federation changes propagate to all enrolled shops without clobbering overrides.
 *   <li>Row IDs are deterministic across shops and invocations.
 *   <li>Clean text serialization across configuration types.
 * </ul>
 */
class ConfigSnapshotIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SOCIETY_A = UUID.fromString("0190b800-0000-7000-8800-000000000001");
    private static final UUID SOCIETY_B = UUID.fromString("0190b800-0000-7000-8800-000000000002");
    private static final UUID SHOP_A1 = UUID.fromString("0190b800-0000-7000-8800-000000000101");
    private static final UUID SHOP_A2 = UUID.fromString("0190b800-0000-7000-8800-000000000102");
    private static final UUID SHOP_B1 = UUID.fromString("0190b800-0000-7000-8800-000000000201");

    private static final String CAP_KEY = "customers.offline_account_cap";
    private static final String RETENTION_KEY = "sync.outbox.retention_days";
    private static final String STALENESS_KEY = "pos.snapshot_staleness_days";

    @Autowired
    ConfigRegistry config;

    @Autowired
    ConfigSnapshotContributor contributor;

    @Autowired
    ConfigChangeLogFanOut fanOut;

    @Autowired
    SnapshotBuilder snapshotBuilder;

    @Autowired
    SystemScope systemScope;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private final ScopeContext centralScope = SystemScope.own(TEST_FEDERATION, null);
    private final ScopeContext societyScopeA = SystemScope.own(SOCIETY_A, null);
    private final ScopeContext tillScopeA1 = SystemScope.own(SOCIETY_A, SHOP_A1);
    private final ScopeContext tillScopeA2 = SystemScope.own(SOCIETY_A, SHOP_A2);
    private final ScopeContext tillScopeB1 = SystemScope.own(SOCIETY_B, SHOP_B1);

    private final Shop shopA1 = new Shop(SOCIETY_A, SHOP_A1);
    private final Shop shopA2 = new Shop(SOCIETY_A, SHOP_A2);
    private final Shop shopB1 = new Shop(SOCIETY_B, SHOP_B1);

    @BeforeEach
    void setupFixtures() {
        JdbcTemplate db = superuserJdbc();
        clean(db);

        // 1. Entities
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) "
                        + "values (?, 'FEDERATION', 'FED', 'Federation', 'ACTIVE') on conflict do nothing",
                TEST_FEDERATION);
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) "
                        + "values (?, 'MPCS', 'MPCS-A', 'Society A', 'ACTIVE')",
                SOCIETY_A);
        db.update(
                "insert into party.entity (entity_id, entity_type, entity_code, legal_name_en, status) "
                        + "values (?, 'MPCS', 'MPCS-B', 'Society B', 'ACTIVE')",
                SOCIETY_B);

        // 2. Shops
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status) "
                        + "values (?, ?, 'S-A1', 'SHOP', 'Shop A1', 'en', 'ACTIVE')",
                SHOP_A1,
                SOCIETY_A);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status) "
                        + "values (?, ?, 'S-A2', 'SHOP', 'Shop A2', 'en', 'ACTIVE')",
                SHOP_A2,
                SOCIETY_A);
        db.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, language, status) "
                        + "values (?, ?, 'S-B1', 'SHOP', 'Shop B1', 'en', 'ACTIVE')",
                SHOP_B1,
                SOCIETY_B);
    }

    @AfterEach
    void cleanAfterwards() {
        clean(superuserJdbc());
    }

    private void clean(JdbcTemplate db) {
        db.update("delete from kernel.change_log where location_id in (?, ?, ?)", SHOP_A1, SHOP_A2, SHOP_B1);
        db.update(
                "delete from kernel.location_snapshot_version where location_id in (?, ?, ?)",
                SHOP_A1,
                SHOP_A2,
                SHOP_B1);
        db.update("delete from kernel.config_value");
        db.update("delete from party.location where location_id in (?, ?, ?)", SHOP_A1, SHOP_A2, SHOP_B1);
        db.update("delete from party.entity_party_directory where entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        db.update("delete from party.entity where entity_id in (?, ?)", SOCIETY_A, SOCIETY_B);
        ((JdbcConfigRegistry) config).invalidate(CAP_KEY);
        ((JdbcConfigRegistry) config).invalidate(RETENTION_KEY);
        ((JdbcConfigRegistry) config).invalidate(STALENESS_KEY);
    }

    @Test
    void fullSnapshotContainsApplicableTillVisibleDefaultsAndExcludesNonVisible() {
        JsonNode snapshot = buildSnapshot(tillScopeA1, 0);

        assertThat(snapshot.path("full").asBoolean()).isTrue();
        assertThat(snapshot.path("tables").has(ConfigSnapshotContributor.TABLE)).isTrue();

        JsonNode configUpserts =
                snapshot.path("tables").path(ConfigSnapshotContributor.TABLE).path("upserts");
        Map<String, String> valuesByKey = extractConfigValues(configUpserts);

        // Verified defaults present
        assertThat(valuesByKey).containsEntry(CAP_KEY, "5000");
        assertThat(valuesByKey).containsEntry(RETENTION_KEY, "7");
        assertThat(valuesByKey).containsEntry(STALENESS_KEY, "3");

        // Non-till-visible keys strictly excluded
        assertThat(valuesByKey).doesNotContainKey("business.timezone");
        assertThat(valuesByKey).doesNotContainKey("security.mfa.freshness");
        assertThat(valuesByKey).doesNotContainKey("m1.bulk.max_rows");
    }

    @Test
    void entityKhataCapOverrideAndCrossSocietyIsolation() {
        // Set offline khata cap override for Society A
        systemScope.inScope(societyScopeA, () -> {
            config.set(CAP_KEY, ConfigScope.entity(SOCIETY_A), "12000", societyScopeA, "entity cap override");
            return null;
        });

        // Society A shops resolve the override
        Map<UUID, Map<String, Object>> rowsA1 =
                systemScope.inScope(tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));
        Map<UUID, Map<String, Object>> rowsA2 =
                systemScope.inScope(tillScopeA2, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA2));
        assertThat(extractValue(rowsA1, CAP_KEY)).isEqualTo("12000");
        assertThat(extractValue(rowsA2, CAP_KEY)).isEqualTo("12000");

        // Society B shop resolves the default (isolated)
        Map<UUID, Map<String, Object>> rowsB1 =
                systemScope.inScope(tillScopeB1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopB1));
        assertThat(extractValue(rowsB1, CAP_KEY)).isEqualTo("5000");
    }

    @Test
    void locationOverridePrecedence() {
        // Entity override for Society A
        systemScope.inScope(societyScopeA, () -> {
            config.set(RETENTION_KEY, ConfigScope.entity(SOCIETY_A), "10", societyScopeA, "society retention");
            return null;
        });

        // Location override for Shop A1
        systemScope.inScope(societyScopeA, () -> {
            config.set(
                    RETENTION_KEY, ConfigScope.location(SOCIETY_A, SHOP_A1), "14", societyScopeA, "shop A1 retention");
            return null;
        });

        // Location > Entity > Default
        Map<UUID, Map<String, Object>> rowsA1 =
                systemScope.inScope(tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));
        Map<UUID, Map<String, Object>> rowsA2 =
                systemScope.inScope(tillScopeA2, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA2));
        Map<UUID, Map<String, Object>> rowsB1 =
                systemScope.inScope(tillScopeB1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopB1));

        assertThat(extractValue(rowsA1, RETENTION_KEY)).isEqualTo("14");
        assertThat(extractValue(rowsA2, RETENTION_KEY)).isEqualTo("10");
        assertThat(extractValue(rowsB1, RETENTION_KEY)).isEqualTo("7");
    }

    @Test
    void federationLevelUpdatePropagationAndOverridesRemainIntact() {
        // Society A sets override for khata cap and location retention
        systemScope.inScope(societyScopeA, () -> {
            config.set(CAP_KEY, ConfigScope.entity(SOCIETY_A), "12000", societyScopeA, "society A cap");
            config.set(
                    RETENTION_KEY, ConfigScope.location(SOCIETY_A, SHOP_A1), "14", societyScopeA, "shop A1 retention");
            return null;
        });

        // Federation updates retention_days at Federation level
        systemScope.inScope(centralScope, () -> {
            config.set(RETENTION_KEY, ConfigScope.federation(), "9", centralScope, "federation default retention");
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), RETENTION_KEY, null, null, "7", "9", true), centralScope);
            return null;
        });

        // Shop A1 keeps location override (14)
        assertThat(extractValue(
                        systemScope.inScope(
                                tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1)),
                        RETENTION_KEY))
                .isEqualTo("14");
        // Shop A2 has no retention override, so it resolves the new Federation value (9)
        assertThat(extractValue(
                        systemScope.inScope(
                                tillScopeA2, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA2)),
                        RETENTION_KEY))
                .isEqualTo("9");
        // Shop B1 has no override, so it resolves the new Federation value (9)
        assertThat(extractValue(
                        systemScope.inScope(
                                tillScopeB1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopB1)),
                        RETENTION_KEY))
                .isEqualTo("9");

        // Society A cap override remains untouched (12000)
        assertThat(extractValue(
                        systemScope.inScope(
                                tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1)),
                        CAP_KEY))
                .isEqualTo("12000");
    }

    @Test
    void updatedKhataCapReachesNextDeltaAndIncrementsVersion() {
        // 1. Establish baseline version for Shop A1 and A2 by logging an initial change
        systemScope.inScope(centralScope, () -> {
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), RETENTION_KEY, null, null, "7", "7", true), centralScope);
            return null;
        });

        Long initialVersionA1 = version(SHOP_A1);
        assertThat(initialVersionA1).isNotNull().isGreaterThan(0L);

        // 2. Society A updates its offline khata cap
        systemScope.inScope(societyScopeA, () -> {
            config.set(CAP_KEY, ConfigScope.entity(SOCIETY_A), "15000", societyScopeA, "increased khata cap");
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), CAP_KEY, SOCIETY_A, null, "5000", "15000", true),
                    societyScopeA);
            return null;
        });

        // 3. Verify snapshot version increments for Society A's shops, but NOT Society B
        Long versionA1 = version(SHOP_A1);
        Long versionA2 = version(SHOP_A2);
        List<Long> versionsB1 = versions(SHOP_B1);

        assertThat(versionA1).isNotNull().isGreaterThan(initialVersionA1);
        assertThat(versionA2).isNotNull().isGreaterThan(initialVersionA1);
        assertThat(versionsB1)
                .containsExactly(
                        initialVersionA1); // Society B received initial federation change, not Society A change

        // 4. Shop A1 downloads delta since initialVersionA1
        JsonNode deltaA1 = buildSnapshot(tillScopeA1, initialVersionA1);

        assertThat(deltaA1.path("full").asBoolean()).isFalse();
        assertThat(deltaA1.path("since").asLong()).isEqualTo(initialVersionA1);
        assertThat(deltaA1.path("version").asLong()).isEqualTo(versionA1);

        JsonNode deltaRows =
                deltaA1.path("tables").path(ConfigSnapshotContributor.TABLE).path("upserts");
        assertThat(deltaRows.isArray()).isTrue();
        assertThat(deltaRows.size()).isEqualTo(1);

        JsonNode changedRow = deltaRows.get(0);
        assertThat(changedRow.path("rowId").asText())
                .isEqualTo(ConfigSnapshotContributor.rowId(CAP_KEY).toString());
        assertThat(changedRow.path("data").path("key").asText()).isEqualTo(CAP_KEY);
        assertThat(changedRow.path("data").path("value").asText()).isEqualTo("15000");

        // 5. Society B requests delta and gets no changes
        JsonNode deltaB1 = buildSnapshot(tillScopeB1, initialVersionA1);
        assertThat(deltaB1.path("full").asBoolean()).isFalse();
        JsonNode deltaB1Rows =
                deltaB1.path("tables").path(ConfigSnapshotContributor.TABLE).path("upserts");
        assertThat(deltaB1Rows.size()).isEqualTo(0);
    }

    @Test
    void stableConfigurationRowIdsAcrossShopsAndCalls() {
        UUID expectedCapId = UUID.nameUUIDFromBytes(CAP_KEY.getBytes(StandardCharsets.UTF_8));
        UUID expectedRetentionId = UUID.nameUUIDFromBytes(RETENTION_KEY.getBytes(StandardCharsets.UTF_8));

        assertThat(ConfigSnapshotContributor.rowId(CAP_KEY)).isEqualTo(expectedCapId);
        assertThat(ConfigSnapshotContributor.rowId(RETENTION_KEY)).isEqualTo(expectedRetentionId);

        Map<UUID, Map<String, Object>> rowsA =
                systemScope.inScope(tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));
        Map<UUID, Map<String, Object>> rowsB =
                systemScope.inScope(tillScopeB1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopB1));

        assertThat(rowsA).containsKey(expectedCapId);
        assertThat(rowsB).containsKey(expectedCapId);
    }

    @Test
    void jsonSerializationAcrossConfigurationTypes() {
        Map<UUID, Map<String, Object>> rows =
                systemScope.inScope(tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));

        // INTEGER values formatted cleanly without decimals
        assertThat(extractValue(rows, CAP_KEY)).isEqualTo("5000");
        assertThat(extractValue(rows, RETENTION_KEY)).isEqualTo("7");
        assertThat(extractValue(rows, STALENESS_KEY)).isEqualTo("3");
    }

    @Test
    void fullSnapshotAndDeltaConvergence() {
        // Establish baseline version
        systemScope.inScope(centralScope, () -> {
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), CAP_KEY, null, null, "5000", "5000", true), centralScope);
            return null;
        });

        Long baseVersion = version(SHOP_A1);

        // Change retention setting
        systemScope.inScope(societyScopeA, () -> {
            config.set(RETENTION_KEY, ConfigScope.entity(SOCIETY_A), "12", societyScopeA, "society retention update");
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), RETENTION_KEY, SOCIETY_A, null, "7", "12", true),
                    societyScopeA);
            return null;
        });

        // 1. Value in delta
        JsonNode delta = buildSnapshot(tillScopeA1, baseVersion);
        String deltaValue = delta.path("tables")
                .path(ConfigSnapshotContributor.TABLE)
                .path("upserts")
                .get(0)
                .path("data")
                .path("value")
                .asText();
        assertThat(deltaValue).isEqualTo("12");

        // 2. Value in fresh full snapshot
        JsonNode full = buildSnapshot(tillScopeA1, 0);
        Map<String, String> fullValues = extractConfigValues(
                full.path("tables").path(ConfigSnapshotContributor.TABLE).path("upserts"));
        assertThat(fullValues.get(RETENTION_KEY)).isEqualTo(deltaValue);
    }

    @Test
    void nonTillVisibleChangeIsIgnoredByFanOut() {
        ConfigChanged nonVisibleEvent =
                new ConfigChanged(UUID.randomUUID(), "security.mfa.freshness", SOCIETY_A, null, "PT12H", "PT6H", false);

        fanOut.onConfigChanged(nonVisibleEvent, societyScopeA);

        List<Map<String, Object>> changes = superuserJdbc()
                .queryForList("select * from kernel.change_log where location_id in (?, ?)", SHOP_A1, SHOP_A2);
        assertThat(changes).isEmpty();
    }

    @Test
    void rowsMethodFiltersByRequestedRowIds() {
        UUID capId = ConfigSnapshotContributor.rowId(CAP_KEY);
        Map<UUID, Map<String, Object>> filtered = systemScope.inScope(
                tillScopeA1, () -> contributor.rows(ConfigSnapshotContributor.TABLE, shopA1, Set.of(capId)));

        assertThat(filtered).hasSize(1);
        assertThat(filtered).containsKey(capId);
        assertThat(filtered.get(capId)).containsEntry("key", CAP_KEY);
        assertThat(filtered.get(capId)).containsEntry("value", "5000");
    }

    @Test
    void ordinarySocietyCannotTriggerFederationWideShopDiscovery() {
        // Society scope attempting to invoke fan-out with scopeEntityId == null
        ConfigChanged fedEvent = new ConfigChanged(UUID.randomUUID(), RETENTION_KEY, null, null, "7", "10", true);

        // When invoked with societyScopeA, party.change_log_shops(null) returns 0 rows because
        // kernel.scope_entity() != kernel.system_entity()
        systemScope.inScope(societyScopeA, () -> {
            fanOut.onConfigChanged(fedEvent, societyScopeA);
            return null;
        });

        List<Long> versionsA1 = versions(SHOP_A1);
        List<Long> versionsB1 = versions(SHOP_B1);
        assertThat(versionsA1).isEmpty();
        assertThat(versionsB1).isEmpty();
    }

    @Test
    void legitimateEmptyStringVsMissingValueHandling() {
        String emptyKey = "test.till_visible.empty_str";
        String nullKey = "test.till_visible.null_default";
        superuserJdbc()
                .update(
                        """
                        insert into kernel.config_item (key, module, value_type, default_value, scope_kind, change_permission, till_visible, description_en)
                        values (?, 'kernel', 'STRING', cast('""' as jsonb), 'ENTITY', 'sys.config.manage', true, 'test empty')
                        """,
                        emptyKey);
        superuserJdbc()
                .update(
                        """
                        insert into kernel.config_item (key, module, value_type, default_value, scope_kind, change_permission, till_visible, description_en)
                        values (?, 'kernel', 'STRING', cast('null' as jsonb), 'ENTITY', 'sys.config.manage', true, 'test null')
                        """,
                        nullKey);

        try {
            Map<UUID, Map<String, Object>> rows = systemScope.inScope(
                    tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));

            // Legitimate empty string is present as ""
            UUID emptyId = ConfigSnapshotContributor.rowId(emptyKey);
            assertThat(rows).containsKey(emptyId);
            assertThat(rows.get(emptyId)).containsEntry("value", "");

            // Null default is missing from snapshot (omitted)
            UUID nullId = ConfigSnapshotContributor.rowId(nullKey);
            assertThat(rows).doesNotContainKey(nullId);
        } finally {
            superuserJdbc().update("delete from kernel.config_item where key in (?, ?)", emptyKey, nullKey);
        }
    }

    @Test
    void historicalAndFutureDatedValuesResolution() {
        String testKey = "test.till_visible.historical";
        superuserJdbc()
                .update(
                        """
                        insert into kernel.config_item (key, module, value_type, default_value, scope_kind, change_permission, till_visible, description_en)
                        values (?, 'kernel', 'STRING', cast('"default"' as jsonb), 'ENTITY', 'sys.config.manage', true, 'test hist')
                        """,
                        testKey);

        try {
            // Historical value 1 (2 days ago): "v1"
            superuserJdbc()
                    .update(
                            """
                            insert into kernel.config_value (key, scope_entity_id, value, effective_from)
                            values (?, ?, cast('"v1"' as jsonb), now() - interval '2 days')
                            """,
                            testKey,
                            SOCIETY_A);

            // Historical value 2 (1 hour ago, supersedes v1): "v2"
            superuserJdbc()
                    .update(
                            """
                            insert into kernel.config_value (key, scope_entity_id, value, effective_from)
                            values (?, ?, cast('"v2"' as jsonb), now() - interval '1 hour')
                            """,
                            testKey,
                            SOCIETY_A);

            // Future value (tomorrow, should NOT be effective yet): "v3"
            superuserJdbc()
                    .update(
                            """
                            insert into kernel.config_value (key, scope_entity_id, value, effective_from)
                            values (?, ?, cast('"v3"' as jsonb), now() + interval '1 day')
                            """,
                            testKey,
                            SOCIETY_A);

            Map<UUID, Map<String, Object>> rows = systemScope.inScope(
                    tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));

            // Must resolve to v2 (latest effective in past, ignoring future v3)
            assertThat(extractValue(rows, testKey)).isEqualTo("v2");
        } finally {
            superuserJdbc().update("delete from kernel.config_value where key = ?", testKey);
            superuserJdbc().update("delete from kernel.config_item where key = ?", testKey);
        }
    }

    @Test
    void consistencyBetweenAllRowsAndRows() {
        Map<UUID, Map<String, Object>> allRows =
                systemScope.inScope(tillScopeA1, () -> contributor.allRows(ConfigSnapshotContributor.TABLE, shopA1));
        assertThat(allRows).isNotEmpty();

        // Querying rows() with the full key set gives the exact same result
        Map<UUID, Map<String, Object>> explicitRows = systemScope.inScope(
                tillScopeA1, () -> contributor.rows(ConfigSnapshotContributor.TABLE, shopA1, allRows.keySet()));
        assertThat(explicitRows).isEqualTo(allRows);

        // Unknown row ID returns empty map
        UUID unknownId = UUID.randomUUID();
        Map<UUID, Map<String, Object>> unknown = systemScope.inScope(
                tillScopeA1, () -> contributor.rows(ConfigSnapshotContributor.TABLE, shopA1, Set.of(unknownId)));
        assertThat(unknown).isEmpty();
    }

    @Test
    void locationScopedFanOutTargetsOnlyExactShop() {
        // Location-specific change for Shop A1
        ConfigChanged locationEvent =
                new ConfigChanged(UUID.randomUUID(), RETENTION_KEY, SOCIETY_A, SHOP_A1, "7", "14", true);

        systemScope.inScope(societyScopeA, () -> {
            fanOut.onConfigChanged(locationEvent, societyScopeA);
            return null;
        });

        // Shop A1 has a new version in kernel.change_log
        Long versionA1 = version(SHOP_A1);
        assertThat(versionA1).isNotNull().isGreaterThan(0L);

        // Shop A2 (same society, sibling shop) has NO change log entry
        List<Long> versionsA2 = versions(SHOP_A2);
        assertThat(versionsA2).isEmpty();

        // Shop B1 (different society) has NO change log entry
        List<Long> versionsB1 = versions(SHOP_B1);
        assertThat(versionsB1).isEmpty();
    }

    @Test
    void completeEndToEndConfigSetToDeltaFlow() {
        // 1. Establish baseline
        systemScope.inScope(centralScope, () -> {
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), CAP_KEY, null, null, "5000", "5000", true), centralScope);
            return null;
        });
        Long v0 = version(SHOP_A1);

        // 2. Real config.set invocation inside transactional society scope
        systemScope.inScope(societyScopeA, () -> {
            config.set(CAP_KEY, ConfigScope.entity(SOCIETY_A), "20000", societyScopeA, "raise cap to 20000");
            fanOut.onConfigChanged(
                    new ConfigChanged(UUID.randomUUID(), CAP_KEY, SOCIETY_A, null, "5000", "20000", true),
                    societyScopeA);
            return null;
        });

        // 3. Shop A1 downloads delta
        JsonNode deltaA1 = buildSnapshot(tillScopeA1, v0);
        assertThat(deltaA1.path("full").asBoolean()).isFalse();
        assertThat(deltaA1.path("version").asLong()).isGreaterThan(v0);

        JsonNode upserts =
                deltaA1.path("tables").path(ConfigSnapshotContributor.TABLE).path("upserts");
        assertThat(upserts.size()).isEqualTo(1);
        assertThat(upserts.get(0).path("data").path("key").asText()).isEqualTo(CAP_KEY);
        assertThat(upserts.get(0).path("data").path("value").asText()).isEqualTo("20000");

        // 4. Society B shop unaffected
        JsonNode deltaB1 = buildSnapshot(tillScopeB1, v0);
        assertThat(deltaB1.path("tables")
                        .path(ConfigSnapshotContributor.TABLE)
                        .path("upserts")
                        .size())
                .isEqualTo(0);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private Long version(UUID location) {
        return superuserJdbc()
                .queryForObject(
                        "select current_version from kernel.location_snapshot_version where location_id = ?",
                        Long.class,
                        location);
    }

    private List<Long> versions(UUID location) {
        return superuserJdbc()
                .queryForList(
                        "select current_version from kernel.location_snapshot_version where location_id = ?",
                        Long.class,
                        location);
    }

    private JsonNode buildSnapshot(ScopeContext tillScope, long since) {
        return json.valueToTree(systemScope.inScope(
                tillScope, () -> (Object) snapshotBuilder.build(tillScope, since, Duration.ofDays(30))));
    }

    private static Map<String, String> extractConfigValues(JsonNode upsertsArray) {
        Map<String, String> map = new LinkedHashMap<>();
        if (upsertsArray != null && upsertsArray.isArray()) {
            for (JsonNode row : upsertsArray) {
                String key = row.path("data").path("key").asText();
                String value = row.path("data").path("value").asText();
                map.put(key, value);
            }
        }
        return map;
    }

    private static String extractValue(Map<UUID, Map<String, Object>> rows, String key) {
        UUID id = ConfigSnapshotContributor.rowId(key);
        Map<String, Object> data = rows.get(id);
        return data == null ? null : (String) data.get("value");
    }
}
