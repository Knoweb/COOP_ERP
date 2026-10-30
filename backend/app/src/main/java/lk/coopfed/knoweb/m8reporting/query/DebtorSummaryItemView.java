package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Summary view of a debtor (customer / society) exposure.
 *
 * @param counterpartyId the debtor party ID
 * @param counterpartyCode the party code (or empty)
 * @param counterpartyName the legal or display name of the debtor
 * @param creditLimit the credit limit if configured, or null
 * @param openInvoices the total unpaid invoices
 * @param acceptedNotInvoiced accepted orders pending invoicing
 * @param unappliedReceipts receipts on account
 * @param unappliedCredits credit notes unapplied
 * @param netExposure net balance owed by the debtor
 * @param warnThresholdPercent highest threshold reached, or null
 */
public record DebtorSummaryItemView(
        UUID counterpartyId,
        String counterpartyCode,
        String counterpartyName,
        BigDecimal creditLimit,
        BigDecimal openInvoices,
        BigDecimal acceptedNotInvoiced,
        BigDecimal unappliedReceipts,
        BigDecimal unappliedCredits,
        BigDecimal netExposure,
        Integer warnThresholdPercent) {}
