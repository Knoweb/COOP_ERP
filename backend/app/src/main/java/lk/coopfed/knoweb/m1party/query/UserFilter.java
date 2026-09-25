package lk.coopfed.knoweb.m1party.query;

import java.util.UUID;

public record UserFilter(String status, String userKind, UUID cursor, Integer limit) {

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }

        return Math.max(1, Math.min(limit, 100));
    }
}
