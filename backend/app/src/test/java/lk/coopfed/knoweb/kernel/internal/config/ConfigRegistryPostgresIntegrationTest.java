package lk.coopfed.knoweb.kernel.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.ConfigSeeder;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The register against PostgreSQL (19A section 11, "Tests"): resolution precedence, schema
 * rejection, the scope an item allows, the Federation alone for federation-wide values, MFA
 * on a sensitive item, append-only history with audit and event, and the cache emptied by a
 * change. The system entity is the Federation of this test.
 */
class ConfigRegistryPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = UUID.fromString("0190f100-0000-7000-8000-000000000001");
    private static final UUID SOCIETY = UUID.fromString("0190f100-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190f100-0000-7000-8000-000000000101");
    private static final UUID USER = UUID.fromString("0190f100-0000-7000-8000-000000000010");

    @DynamicPropertySource
    static void systemEntity(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", FEDERATION::toString);
    }

    @Autowired
    ConfigRegistry config;

    @Autowired
    ConfigSeeder seeder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanValues() {
        superuserJdbc().execute("delete from kernel.config_value");
        superuserJdbc().execute("delete from kernel.config_item where key like 'test.%'");
        superuserJdbc().execute("update kernel.config_item set default_value = '500' where key = 'm1.bulk.max_rows'");
        ((JdbcConfigRegistry) config).invalidate("m1.bulk.max_rows");
        ((JdbcConfigRegistry) config).invalidate("till.idle_lock");
        ((JdbcConfigRegistry) config).invalidate("security.policy.mfa_enabled");
        for (String pin : List.of("lockout_attempts", "lockout_duration", "history_depth")) {
            ((JdbcConfigRegistry) config).invalidate("security.pin." + pin);
        }
    }

    @Test
    void theRegisterIsSeededAndTheDefaultAnswersUntilAValueIsSet() {
        assertThat(superuserJdbc().queryForList("select key from kernel.config_item order by key", String.class))
                .contains("business.timezone", "m1.bulk.max_rows", "till.idle_lock", "security.policy.mfa_enabled");

        assertThat(inScope(SOCIETY, null, () -> config.getInt("m1.bulk.max_rows", scope(SOCIETY, null), 1)))
                .isEqualTo(500);
        assertThat(inScope(
                        SOCIETY, null, () -> config.getDuration("till.idle_lock", scope(SOCIETY, null), Duration.ZERO)))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(inScope(SOCIETY, null, () -> config.get("business.timezone", scope(SOCIETY, null))))
                .contains("Asia/Colombo");
        assertThat(inScope(SOCIETY, null, () -> config.get("nobody.registered.this", scope(SOCIETY, null))))
                .isEmpty();
    }

    @Test
    void theMostSpecificScopeWins() {
        inScope(SOCIETY, null, () -> {
            config.set("till.idle_lock", ConfigScope.entity(SOCIETY), "PT10M", scope(SOCIETY, null), "entity");
            return null;
        });
        inScope(SOCIETY, null, () -> {
            config.set("till.idle_lock", ConfigScope.location(SOCIETY, SHOP), "PT2M", scope(SOCIETY, null), "shop");
            return null;
        });

        assertThat(inScope(
                        SOCIETY, SHOP, () -> config.getDuration("till.idle_lock", scope(SOCIETY, SHOP), Duration.ZERO)))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(inScope(
                        SOCIETY, null, () -> config.getDuration("till.idle_lock", scope(SOCIETY, null), Duration.ZERO)))
                .isEqualTo(Duration.ofMinutes(10));
        // Another entity is not touched by the society's values.
        assertThat(inScope(
                        FEDERATION,
                        null,
                        () -> config.getDuration("till.idle_lock", scope(FEDERATION, null), Duration.ZERO)))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aChangeIsAppendedAuditedPublishedAndSeenAtOnce() {
        inScope(FEDERATION, null, () -> {
            config.set("m1.bulk.max_rows", ConfigScope.federation(), "1000", scope(FEDERATION, null), "bigger files");
            return null;
        });
        inScope(FEDERATION, null, () -> {
            config.set("m1.bulk.max_rows", ConfigScope.federation(), "1200", scope(FEDERATION, null), null);
            return null;
        });

        assertThat(inScope(SOCIETY, null, () -> config.getInt("m1.bulk.max_rows", scope(SOCIETY, null), 1)))
                .isEqualTo(1200);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.config_value where key = 'm1.bulk.max_rows'", Long.class))
                .isEqualTo(2L);

        assertThat(kernel.committedAudit())
                .extracting(r -> r.eventType())
                .containsExactly("CONFIG_CHANGED", "CONFIG_CHANGED");
        List<Object> events = List.copyOf(kernel.committedEvents());
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOfSatisfying(ConfigChanged.class, event -> {
            assertThat(event.key()).isEqualTo("m1.bulk.max_rows");
            assertThat(event.before()).isEqualTo("1000");
            assertThat(event.after()).isEqualTo("1200");
        });
    }

    @Test
    void aValueOutsideTheSchemaOrOfTheWrongTypeIsRefused() {
        assertThatThrownBy(() -> inScope(FEDERATION, null, () -> {
                    config.set("m1.bulk.max_rows", ConfigScope.federation(), "9000", scope(FEDERATION, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");
        assertThatThrownBy(() -> inScope(FEDERATION, null, () -> {
                    config.set("m1.bulk.max_rows", ConfigScope.federation(), "many", scope(FEDERATION, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("till.idle_lock", ConfigScope.entity(SOCIETY), "PT3H", scope(SOCIETY, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("no.such.key", ConfigScope.entity(SOCIETY), "1", scope(SOCIETY, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.key_unknown");
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void theScopeMustBeOneTheItemAllowsAndTheEntityTheCallersOwn() {
        // A federation-wide item cannot be set per entity, and not by a society.
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("m1.bulk.max_rows", ConfigScope.entity(SOCIETY), "100", scope(SOCIETY, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.scope_invalid");
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("m1.bulk.max_rows", ConfigScope.federation(), "100", scope(SOCIETY, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.federation_only");
        // A society cannot set another entity's value.
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("till.idle_lock", ConfigScope.entity(FEDERATION), "PT1M", scope(SOCIETY, null), null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.owner_mismatch");
    }

    @Test
    void aSensitiveItemNeedsAFreshSecondFactor() {
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set(
                            "security.policy.mfa_enabled",
                            ConfigScope.entity(SOCIETY),
                            "false",
                            scope(SOCIETY, null),
                            null);
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("mfa.required");

        ScopeContext withMfa = scope(SOCIETY, null, Instant.now());
        inScope(SOCIETY, null, () -> {
            config.set("security.policy.mfa_enabled", ConfigScope.entity(SOCIETY), "false", withMfa, "test");
            return null;
        });
        assertThat(inScope(
                        SOCIETY,
                        null,
                        () -> config.getBoolean("security.policy.mfa_enabled", scope(SOCIETY, null), true)))
                .isFalse();
    }

    @Test
    void aModuleDefaultUpdatesARegisteredItemAndAnUnknownKeyFailsTheStart() {
        seeder.addDefaults(Map.of("m1.bulk.max_rows", "750"));

        assertThat(inScope(SOCIETY, null, () -> config.getInt("m1.bulk.max_rows", scope(SOCIETY, null), 1)))
                .isEqualTo(750);

        // An unregistered key used to become an untyped, ENTITY-scoped, not sensitive string item
        // that set() then accepted any text for; now it is a typed entry in config-items.yaml or
        // nothing.
        assertThatThrownBy(() -> seeder.addDefaults(Map.of("test.new_setting", "hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.new_setting");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.config_item where key = 'test.new_setting'",
                                Integer.class))
                .isZero();
        // And a default outside the item's schema is refused the same way it would be at set().
        assertThatThrownBy(() -> seeder.addDefaults(Map.of("m1.bulk.max_rows", "0")))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");
    }

    @Test
    void thePinPolicyCanOnlyBeTightenedByAnEntity() {
        // Doc 19 section 2.1: a PIN is acceptable only because of 5 attempts, a 15-minute lockout
        // and no reuse of the last 3; an entity may go stricter, never looser.
        ScopeContext withMfa = scope(SOCIETY, null, Instant.now());

        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("security.pin.lockout_attempts", ConfigScope.entity(SOCIETY), "6", withMfa, "looser");
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("security.pin.lockout_duration", ConfigScope.entity(SOCIETY), "PT5M", withMfa, "looser");
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");
        assertThatThrownBy(() -> inScope(SOCIETY, null, () -> {
                    config.set("security.pin.history_depth", ConfigScope.entity(SOCIETY), "1", withMfa, "looser");
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("config.value_invalid");

        inScope(SOCIETY, null, () -> {
            config.set("security.pin.lockout_attempts", ConfigScope.entity(SOCIETY), "3", withMfa, "stricter");
            config.set("security.pin.lockout_duration", ConfigScope.entity(SOCIETY), "PT1H", withMfa, "stricter");
            config.set("security.pin.history_depth", ConfigScope.entity(SOCIETY), "5", withMfa, "stricter");
            return null;
        });
        assertThat(inScope(
                        SOCIETY, null, () -> config.getInt("security.pin.lockout_attempts", scope(SOCIETY, null), 5)))
                .isEqualTo(3);
    }

    private static ScopeContext scope(UUID entity, UUID location) {
        return scope(entity, location, null);
    }

    private static ScopeContext scope(UUID entity, UUID location, Instant mfaAt) {
        Scope active = new Scope(entity, location);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                mfaAt,
                Locale.ENGLISH,
                Ids.next());
    }

    private <T> T inScope(UUID entity, UUID location, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString(),
                    location == null ? "" : location.toString());
            return work.get();
        });
    }
}
