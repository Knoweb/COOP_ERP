package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * One entity/location pair a principal may act in (doc 19 §1, claim {@code scopes}).
 * A null location means the whole entity.
 */
public record Scope(
        UUID entityId,
        UUID locationId) {

    public Scope {
        if (entityId == null) {
            throw new IllegalArgumentException("A scope always names an entity");
        }
    }

    public boolean isEntityWide() {
        return locationId == null;
    }
}
