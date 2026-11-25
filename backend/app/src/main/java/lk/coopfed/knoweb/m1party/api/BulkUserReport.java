package lk.coopfed.knoweb.m1party.api;

import java.util.List;

/**
 * Outcome of a bulk user import operation (CAT-09).
 */
public record BulkUserReport(String status, int imported, int failed, List<Problem> problems) {

    public static final String IMPORTED = "IMPORTED";
    public static final String REJECTED = "REJECTED";
    public static final String EMPTY = "EMPTY";

    public BulkUserReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public record Problem(String username, String field, String reason) {}
}
