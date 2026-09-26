package lk.coopfed.knoweb.kernel.internal.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigSeeder;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.internal.security.StepUp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The register on {@code kernel.config_item} and {@code kernel.config_value} (19A section 11),
 * replacing the 17A in-memory stub. Reads resolve location, then entity, then federation, then
 * the item's default, through a per-instance cache that a change empties for its key (on this
 * instance at once; on the others through {@code config.changed.v1}, and by expiry in any case).
 *
 * <p>Writes go through {@link #set}: the item's type and schema, the scope the item allows, the
 * Federation alone for a federation-wide value, a fresh second factor for a sensitive item, then
 * the append-only row, {@code CONFIG_CHANGED} with before and after, and the event. The item's
 * {@code change_permission} is checked when K-03b gives the kernel a permission resolver; until
 * then the caller's handler carries the permission.
 */
@Component
public class JdbcConfigRegistry implements ConfigRegistry, ConfigSeeder {

    static final String AUDIT_CHANGED = "CONFIG_CHANGED";

    /** How long a resolved value may be served on an instance that missed the change event. */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** How fresh a second factor must be for a sensitive item (doc 19 section 8; K-02 refines it). */
    private final JdbcTemplate jdbc;

    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final SystemScope system;
    private final Clock clock;
    private final StepUp stepUp;
    private final ConfigItemSeedLoader seeds;
    private final String businessTimezone;

    private final Cache<CacheKey, Optional<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public JdbcConfigRegistry(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events,
            SystemScope system,
            Clock clock,
            StepUp stepUp,
            ConfigItemSeedLoader seeds,
            @Value("${coop-erp.business-timezone}") String businessTimezone) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.events = events;
        this.system = system;
        this.clock = clock;
        this.stepUp = stepUp;
        this.seeds = seeds;
        this.businessTimezone = businessTimezone;
    }

    // ---- reads --------------------------------------------------------------------------------

    @Override
    public Optional<String> get(String key, ScopeContext scope) {
        // The one value that must be right before the register is: the zone the application runs in.
        if ("business.timezone".equals(key)) {
            Optional<String> configured = resolve(key, scope);
            return configured.isPresent() ? configured : Optional.of(businessTimezone);
        }
        return resolve(key, scope);
    }

    private Optional<String> resolve(String key, ScopeContext scope) {
        UUID entityId = scope == null ? null : scope.entityId();
        UUID locationId = scope == null ? null : scope.locationId();
        return cache.get(new CacheKey(key, entityId, locationId), ignored -> load(key, entityId, locationId));
    }

    private Optional<String> load(String key, UUID entityId, UUID locationId) {
        Optional<ConfigItem> item = findItem(key);

        if (item.isEmpty()) {
            return Optional.empty();
        }

        // The most specific row wins: location, then entity, then federation; the query orders
        // them so and takes the latest effective row of the first scope that has one.
        List<String> found = jdbc.query(
                """
                select value from kernel.config_value
                 where key = ?
                   and effective_from <= now()
                   and (scope_entity_id is null
                        or (scope_entity_id = ? and (scope_location_id is null or scope_location_id = ?)))
                 order by (scope_location_id is not null) desc, (scope_entity_id is not null) desc, effective_from desc
                 limit 1
                """,
                (rs, rowNum) -> rs.getString("value"),
                key,
                entityId,
                locationId);

        try {
            JsonNode value = found.isEmpty() ? item.get().defaultValue() : json.readTree(found.get(0));
            return Optional.ofNullable(ConfigValues.text(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("kernel.config_value holds a value that is not JSON: " + key, e);
        }
    }

    // ---- writes -------------------------------------------------------------------------------

    @Override
    public void set(String key, ConfigScope scope, String value, ScopeContext ctx, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ConfigRegistry.set was called outside a transaction");
        }

        Objects.requireNonNull(scope, "a value has a scope");

        ConfigItem item =
                findItem(key).orElseThrow(() -> new ProblemException("config.key_unknown", Map.of("key", key)));

        requireScopeAllowed(item, scope);

        if (ctx == null || ctx.entityId() == null) {
            throw new ProblemException("scope.required");
        }

        if (scope.entityId() == null) {
            // Federation-wide: the Federation's own doing, and nobody else's.
            Optional<UUID> federation = system.own().map(ScopeContext::entityId);
            if (federation.isEmpty() || !federation.get().equals(ctx.entityId())) {
                throw new ProblemException("config.federation_only", Map.of("key", key));
            }
        } else if (!scope.entityId().equals(ctx.entityId())) {
            throw new ProblemException("config.owner_mismatch", Map.of("key", key));
        }

        if (item.sensitive() && !stepUp.isFresh(ctx)) {
            throw new ProblemException("mfa.required", Map.of("key", key));
        }

        JsonNode parsed = ConfigValues.parse(item, value, json);
        String before = currentText(key, scope);
        String after = ConfigValues.text(parsed);

        jdbc.update(
                """
                insert into kernel.config_value (key, scope_entity_id, scope_location_id, value, changed_by, reason)
                values (?, ?, ?, cast(? as jsonb), ?, ?)
                """,
                key,
                scope.entityId(),
                scope.locationId(),
                parsed.toString(),
                ctx.userId(),
                reason);

        // This instance's cache empties when the value is real: before the commit a concurrent
        // reader would cache the old value again, and a rollback would leave the new one cached.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(key);
            }
        });

        UUID changeId = Ids.next();

        audit.record(
                AUDIT_CHANGED,
                Subject.of("config_value", changeId),
                Map.of("key", key, "value", String.valueOf(before)),
                Map.of(
                        "key", key,
                        "value", String.valueOf(after),
                        "scopeEntityId", String.valueOf(scope.entityId()),
                        "scopeLocationId", String.valueOf(scope.locationId())),
                ctx,
                reason);

        events.publish(new ConfigChanged(
                changeId, key, scope.entityId(), scope.locationId(), before, after, item.tillVisible()));
    }

    /** Empties every cached resolution of a key, on this instance. */
    public void invalidate(String key) {
        cache.asMap().keySet().removeIf(cached -> cached.key().equals(key));
    }

    // ---- seeding ------------------------------------------------------------------------------

    /**
     * A module's defaults (seed/&lt;module&gt;/config.yaml): the default of a registered item is
     * updated; a key the register does not know becomes a plain ENTITY-scoped string item of
     * that module, so that a module can ship a setting before the register lists it.
     */
    @Override
    public void addDefaults(Map<String, String> items) {
        Map<String, String> copy = new HashMap<>(items);
        seeds.registerModuleDefaults(copy);
        copy.keySet().forEach(this::invalidate);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Optional<ConfigItem> findItem(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(ConfigItem.SELECT + " where key = ?", ConfigItem.mapper(json), key).stream()
                .findFirst();
    }

    private String currentText(String key, ConfigScope scope) {
        List<String> rows = jdbc.query(
                """
                select value from kernel.config_value
                 where key = ? and scope_entity_id is not distinct from ? and scope_location_id is not distinct from ?
                 order by effective_from desc limit 1
                """,
                (rs, rowNum) -> rs.getString("value"),
                key,
                scope.entityId(),
                scope.locationId());
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return ConfigValues.text(json.readTree(rows.get(0)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return rows.get(0);
        }
    }

    private static void requireScopeAllowed(ConfigItem item, ConfigScope scope) {
        boolean allowed =
                switch (item.scopeKind()) {
                    case FEDERATION -> scope.entityId() == null && scope.locationId() == null;
                    case ENTITY -> scope.locationId() == null;
                    case LOCATION -> true;
                };
        if (!allowed) {
            throw new ProblemException(
                    "config.scope_invalid",
                    Map.of("key", item.key(), "scopeKind", item.scopeKind().name()));
        }
    }

    private record CacheKey(String key, UUID entityId, UUID locationId) {}
}
