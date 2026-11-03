package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Monthly debtor list report for the caller's permitted scope.
 */
public record DebtorsReport(
        LocalDate asOf,
        List<DebtorSummaryItemView> items,
        BigDecimal totalOpenInvoices,
        BigDecimal totalUnappliedReceipts,
        BigDecimal totalUnappliedCredits,
        BigDecimal totalNetExposure) {}
