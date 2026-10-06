package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where an invoice stands after its corrections and payments (doc 24 section 9.4): what credit
 * notes took off ({@code creditedAmount}), what payments settled ({@code settledAmount}, net of
 * bounced cheques), the amount still due (gross less both), whether the buyer disputes it now, and
 * its payment state: OPEN (nothing settled), PART_PAID, or SETTLED (nothing due).
 */
public record InvoiceBalance(
        UUID invoiceId,
        BigDecimal creditedAmount,
        BigDecimal settledAmount,
        BigDecimal amountDue,
        boolean disputed,
        String disputeReason,
        String paymentState) {

    public static final String OPEN = "OPEN";
    public static final String PART_PAID = "PART_PAID";
    public static final String SETTLED = "SETTLED";

    /** SETTLED when nothing is due, PART_PAID when a payment settled part of it, OPEN otherwise. */
    public static String paymentState(BigDecimal settled, BigDecimal due) {
        if (due.signum() <= 0) {
            return SETTLED;
        }
        return settled.signum() > 0 ? PART_PAID : OPEN;
    }
}
