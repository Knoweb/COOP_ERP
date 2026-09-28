package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The buyer's exposure with the seller (doc 24 section 3.8; 24A section 6.3): open invoice balances
 * plus accepted-not-invoiced orders at their tier prices, less receipts held on account (credit
 * notes are always applied to their invoice here, so there are no unapplied credits). Compared
 * with the relationship's credit limit (M1) to warn, never to block (ADR-12).
 *
 * @param creditLimit null when the relationship sets none
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
        BigDecimal amount,
        Integer warnThresholdPercent,
        Instant asOf) {}
