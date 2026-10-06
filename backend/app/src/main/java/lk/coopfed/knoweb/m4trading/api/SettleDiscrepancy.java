package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * SettleDiscrepancy (24A section 6, demo scope): the seller accepts the buyer's count. The invoice
 * bills the received quantity (DR-2), so a short quantity was never billed and is settled with no
 * money; damaged quantity the invoice charged is credited by a credit note issued in the same act.
 */
public record SettleDiscrepancy(UUID discrepancyId, String reason) {}
