package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * A configuration value changed (doc 19 section 8: "every change raises config.changed.v1 with
 * before/after"). Every instance empties its cache for the key on it; the shop snapshot picks
 * up a till-visible key from it (K-08).
 *
 * @param changeId      the id of this change, the aggregate of the event
 * @param scopeEntityId the entity the value is for; null for a federation-wide value
 * @param scopeLocation the location the value is for; null unless location-scoped
 */
public record ConfigChanged(
        UUID changeId,
        String key,
        UUID scopeEntityId,
        UUID scopeLocation,
        String before,
        String after,
        boolean tillVisible)
        implements DomainEvent {

    public static final String TYPE = "config.changed.v1";
}
