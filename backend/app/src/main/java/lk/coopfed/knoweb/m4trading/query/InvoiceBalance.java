package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where an invoice stands after its corrections (doc 24 section 9.4): what credit notes took off
 * ({@code creditedAmount}), what payments settled ({@code settledAmount}, zero until M4-09), the
 * amount still due (gross less both), and whether the buyer disputes it now.
 */
public record InvoiceBalance(
        UUID invoiceId,
        BigDecimal creditedAmount,
        BigDecimal settledAmount,
        BigDecimal amountDue,
        boolean disputed,
        String disputeReason) {}
