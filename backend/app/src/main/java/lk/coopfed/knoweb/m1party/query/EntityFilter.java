package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

public record EntityFilter(String status, String district, UUID cursor, Integer limit) {

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }

        return Math.max(1, Math.min(limit, 100));
    }
}
