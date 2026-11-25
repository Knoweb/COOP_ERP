package lk.coopfed.knoweb.kernel.internal.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Snapshot contributor for till-visible configuration items (doc 32 section 5.1; K-08, K-11).
 *
 * <p>Serves the {@code "config"} snapshot table for a shop's till:
 * <ul>
 *   <li>Only items with {@code till_visible = true} in {@code kernel.config_item}.
 *   <li>Follows the exact {@link lk.coopfed.knoweb.kernel.api.ConfigRegistry} resolution order:
 *       location-specific override &gt; entity-wide override &gt; federation-wide override &gt; item default.
 *   <li>Deterministic, stable row IDs derived as UUIDv3 from the configuration key.
 *   <li>Values formatted as text matching {@link ConfigValues#text(JsonNode)}, serialized
 *       as {@code {"key": ..., "value": ...}} compatible with the Kotlin till.
 * </ul>
 */
@Component
public class ConfigSnapshotContributor implements SnapshotContributor {

    public static final String TABLE = "config";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConfigSnapshotContributor(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Deterministic UUIDv3 row ID derived from a configuration key string. */
    public static UUID rowId(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Set<String> tables() {
        return Set.of(TABLE);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        if (!TABLE.equals(table) || shop == null) {
            return Map.of();
        }
        return resolveRows(shop, null);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (!TABLE.equals(table) || shop == null || rowIds == null || rowIds.isEmpty()) {
            return Map.of();
        }
        return resolveRows(shop, Set.copyOf(rowIds));
    }

    private Map<UUID, Map<String, Object>> resolveRows(Shop shop, Set<UUID> filterRowIds) {
        // The most specific row wins: location, then entity, then federation, then default;
        // DISTINCT ON (i.key) picks the first matching row according to the order by clause.
        Map<UUID, Map<String, Object>> result = new LinkedHashMap<>();

        jdbc.query(
                """
                select distinct on (i.key)
                       i.key,
                       coalesce(v.value, i.default_value) as val
                  from kernel.config_item i
                  left join kernel.config_value v on v.key = i.key
                       and v.effective_from <= now()
                       and (v.scope_entity_id is null
                            or (v.scope_entity_id = ? and (v.scope_location_id is null or v.scope_location_id = ?)))
                 where i.till_visible = true
                 order by i.key,
                       (v.scope_location_id is not null) desc,
                       (v.scope_entity_id is not null) desc,
                       v.effective_from desc
                """,
                rs -> {
                    String key = rs.getString("key");
                    UUID rowId = rowId(key);
                    if (filterRowIds != null && !filterRowIds.contains(rowId)) {
                        return;
                    }

                    String rawVal = rs.getString("val");
                    if (rawVal == null) {
                        return;
                    }
                    String textValue;
                    try {
                        JsonNode node = json.readTree(rawVal);
                        textValue = ConfigValues.text(node);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Failed to parse config value JSON for key: " + key, e);
                    }
                    if (textValue == null) {
                        return;
                    }

                    result.put(rowId, Map.of("key", key, "value", textValue));
                },
                shop.ownerEntityId(),
                shop.locationId());

        return result;
    }
}
