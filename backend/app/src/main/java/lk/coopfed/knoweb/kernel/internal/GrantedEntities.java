package lk.coopfed.knoweb.kernel.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serialises the entity grant set carried by ScopeContext into the PostgreSQL uuid[]
 * literal stored in the transaction-local app.granted_entities setting.
 */
public final class GrantedEntities {

    private GrantedEntities() {
    }

    public static String settingValue(Set<UUID> entities) {
        if (entities == null || entities.isEmpty()) {
            return "{}";
        }

        return entities.stream()
                .sorted()
                .map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
    }
}
