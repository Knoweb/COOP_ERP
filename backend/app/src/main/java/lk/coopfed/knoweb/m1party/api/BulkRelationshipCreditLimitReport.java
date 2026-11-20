package lk.coopfed.knoweb.m1party.api;

import java.util.List;

/**
 * Result of setting relationship credit limits in bulk (CAT-14).
 */
public record BulkRelationshipCreditLimitReport(String status, int updatedCount, List<Problem> problems) {

    public static final String UPDATED = "UPDATED";
    public static final String REJECTED = "REJECTED";

    public BulkRelationshipCreditLimitReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public record Problem(String identifier, String field, String code) {}
}
