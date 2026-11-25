package lk.coopfed.knoweb.m1party.api;

import java.util.List;

/**
 * Result of a network bulk registration (CAT-08). Status is REGISTERED when all items passed
 * validation and committed, or REJECTED when one or more problems prevented registration.
 */
public record BulkNetworkReport(
        String status,
        int shopsRegistered,
        int positionsRegistered,
        int usersRegistered,
        int devicesRegistered,
        List<Problem> problems) {

    public static final String REGISTERED = "REGISTERED";
    public static final String REJECTED = "REJECTED";

    public BulkNetworkReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** A validation failure with category, entity/item identifier, field, and localized error code. */
    public record Problem(String category, String identifier, String field, String code) {}
}
