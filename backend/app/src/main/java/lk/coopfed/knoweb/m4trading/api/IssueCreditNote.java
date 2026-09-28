package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * IssueCreditNote (24A section 6): the seller credits its own issued invoice. Either it settles the
 * buyer's discrepancy on a GRN of that invoice ({@code discrepancyId}: the lines are the short and
 * damaged quantities of the discrepancy at the invoice's price and VAT rate), or it names the
 * invoice lines and quantities to credit ({@code lines}). One of the two, never both.
 */
public record IssueCreditNote(UUID invoiceId, UUID discrepancyId, List<Line> lines, String reason) {

    public IssueCreditNote {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A quantity of one invoice line, credited at that line's price and VAT rate. */
    public record Line(UUID invoiceLineId, BigDecimal qty) {}
}
