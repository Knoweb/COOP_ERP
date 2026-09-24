package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;

/**
 * What a bulk registration answers (21A sections 5 and 8): one line per data row of the file,
 * in file order, and the totals. {@code status} is {@code REGISTERED} when every row was
 * registered, {@code REJECTED} when at least one row had a problem and none was registered.
 *
 * <p>A problem names the column and a message id from the {@code bulk.*} catalogue, so the
 * screen shows it in the caller's language beside the row.
 */
public record ValidationReport(String status, int rows, int registered, int rejected, List<RowResult> results) {

    public static final String REGISTERED = "REGISTERED";
    public static final String REJECTED = "REJECTED";

    /** One data row: its line number in the file (the header is line 1), its code, and the outcome. */
    public record RowResult(int line, String entityCode, String status, UUID entityId, List<RowProblem> problems) {

        public static final String OK = "OK";
        public static final String ERROR = "ERROR";
    }

    /** A column that failed a rule, and the rule's message id. */
    public record RowProblem(String field, String code) {}
}
