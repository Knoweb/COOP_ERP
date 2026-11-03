package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Summary view of a creditor (supplier / distributor) liability.
 *
 * @param counterpartyId the creditor party ID
 * @param counterpartyCode the party code (or empty)
 * @param counterpartyName the legal or display name of the creditor
 * @param openInvoices open payables
 * @param acceptedNotInvoiced accepted orders not yet invoiced
 * @param unappliedPayments unapplied payments/receipts held on account
 * @param unappliedCredits credit notes unapplied
 * @param netLiability net amount owed to the creditor
 */
public record CreditorSummaryItemView(
        UUID counterpartyId,
        String counterpartyCode,
        String counterpartyName,
        BigDecimal openInvoices,
        BigDecimal acceptedNotInvoiced,
        BigDecimal unappliedPayments,
        BigDecimal unappliedCredits,
        BigDecimal netLiability) {}
