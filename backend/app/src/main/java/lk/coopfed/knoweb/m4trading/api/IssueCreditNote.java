package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * IssueCreditNote (24A section 6): the seller credits chosen quantities of lines of its own issued
 * invoice, at their prices and VAT rates. A discrepancy is settled by {@link SettleDiscrepancy},
 * which issues a credit note only for billed quantity that should not have been billed.
 */
public record IssueCreditNote(UUID invoiceId, List<Line> lines, String reason) {

    public IssueCreditNote {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A quantity of one invoice line, credited at that line's price and VAT rate. */
    public record Line(UUID invoiceLineId, BigDecimal qty) {}
}
