package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Monthly creditor list report for the caller's permitted scope.
 */
public record CreditorsReport(
        LocalDate asOf,
        List<CreditorSummaryItemView> items,
        BigDecimal totalOpenInvoices,
        BigDecimal totalUnappliedPayments,
        BigDecimal totalUnappliedCredits,
        BigDecimal totalNetLiability) {}
