package lk.coopfed.knoweb.m7customers.api;

import java.util.List;

/**
 * Result of setting customer credit limits in bulk (CAT-14).
 */
public record BulkCustomerCreditLimitReport(String status, int updatedCount, List<Problem> problems) {

    public static final String UPDATED = "UPDATED";
    public static final String REJECTED = "REJECTED";

    public BulkCustomerCreditLimitReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public record Problem(String identifier, String field, String code) {}
}
