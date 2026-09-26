package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

/**
 * What the register list is narrowed by. {@code query} is the text of the search box (21A
 * section 8): a prefix of the entity code or a part of one of the three legal names; null or
 * blank means no search.
 */
public record EntityFilter(String status, String district, String query, UUID cursor, Integer limit) {

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }

        return Math.max(1, Math.min(limit, 100));
    }
}
