package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

/**
 * What the location list is narrowed by. {@code entityId} names the entity whose locations to
 * list (CR-21A-4, for 22A's {@code listLocations(entity)} prerequisite): a FEDERATION_VIEW or
 * EXTERNAL_TIMEBOXED caller may name any entity it can see; an OWN caller may only name its own
 * entity, and naming another gets no rows (row-level security already restricts an OWN caller to
 * its own entity, so the extra filter only ever narrows what RLS would return, never widens it).
 * Null means no narrowing by entity.
 */
public record LocationFilter(String status, String locationType, UUID entityId, UUID cursor, Integer limit) {

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }

        return Math.max(1, Math.min(limit, 100));
    }
}
