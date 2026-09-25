package lk.coopfed.knoweb.kernel.internal.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.RowMapper;

/** One row of the register, {@code kernel.config_item}. */
record ConfigItem(
        String key,
        ValueType valueType,
        JsonNode schema,
        JsonNode defaultValue,
        ScopeKind scopeKind,
        String changePermission,
        boolean sensitive,
        boolean tillVisible,
        String module) {

    enum ValueType {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DURATION,
        JSON
    }

    /** The finest scope a value of the item may be set at; a coarser one is always allowed. */
    enum ScopeKind {
        FEDERATION,
        ENTITY,
        LOCATION
    }

    static final String SELECT = "select key, value_type, schema, default_value, scope_kind, change_permission,"
            + " sensitive, till_visible, module from kernel.config_item";

    static RowMapper<ConfigItem> mapper(com.fasterxml.jackson.databind.ObjectMapper json) {
        return (rs, rowNum) -> {
            try {
                return new ConfigItem(
                        rs.getString("key"),
                        ValueType.valueOf(rs.getString("value_type")),
                        json.readTree(rs.getString("schema")),
                        json.readTree(rs.getString("default_value")),
                        ScopeKind.valueOf(rs.getString("scope_kind")),
                        rs.getString("change_permission"),
                        rs.getBoolean("sensitive"),
                        rs.getBoolean("till_visible"),
                        rs.getString("module"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("kernel.config_item holds a value that is not JSON", e);
            }
        };
    }
}
