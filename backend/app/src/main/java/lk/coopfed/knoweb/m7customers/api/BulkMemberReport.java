package lk.coopfed.knoweb.m7customers.api;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a bulk member import operation (CAT-10).
 */
public record BulkMemberReport(
        UUID importId,
        String status,
        int totalRows,
        int registeredCount,
        int updatedCount,
        int failedCount,
        List<MemberProblem> problems) {

    public static final String IMPORTED = "IMPORTED";
    public static final String REJECTED = "REJECTED";
    public static final String EMPTY = "EMPTY";

    public BulkMemberReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public record MemberProblem(int rowNumber, String nameOrPhone, String field, String reason) {}
}
