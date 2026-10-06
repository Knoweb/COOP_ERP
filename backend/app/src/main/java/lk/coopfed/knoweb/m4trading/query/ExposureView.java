package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The buyer's exposure with the seller (doc 24 section 3.8; 24A section 6.3): open invoice balances
 * plus accepted-not-invoiced orders at their tier prices, less receipts held on account and less
 * what credit notes hold unapplied (CR-24A-3 item 2: a credit note's money beyond what its invoice
 * still owed). Compared with the relationship's credit limit (M1) to warn, never to block (ADR-12).
 *
 * @param creditLimit null when the relationship sets none
 * @param unappliedCredits what the seller's credit notes to the buyer hold unapplied
 * @param warnThresholdPercent the highest configured threshold the amount has reached, or null
 */
public record ExposureView(
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal creditLimit,
        BigDecimal openInvoices,
        BigDecimal acceptedNotInvoiced,
        BigDecimal unappliedReceipts,
        BigDecimal unappliedCredits,
        BigDecimal amount,
        Integer warnThresholdPercent,
        Instant asOf) {}
