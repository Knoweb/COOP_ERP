package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * IssueDebitNote: the seller debits chosen quantities of lines of its own issued
 * invoice, at their prices and VAT rates.
 */
public record IssueDebitNote(UUID invoiceId, List<Line> lines, String reason) {

    public IssueDebitNote {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A quantity of one invoice line, debited at that line's price and VAT rate. */
    public record Line(UUID invoiceLineId, BigDecimal qty) {}
}
