package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SetMrpPolicy (23A section 7; doc 23 section 3.4): how the printed MRP of the batches on the
 * shelf bounds the selling price of a SKU at the caller's shops. AUTO_LOWEST: the lowest printed
 * MRP in stock; BARCODE_RESOLVED: the scanned batch's MRP, else the lowest; PICKER: the cashier
 * picks the batch when the MRPs differ by more than the gap. The owner is the caller's entity; a
 * society's row wins over the Federation's for the same SKU (EffectivePolicy).
 *
 * @param policy     AUTO_LOWEST, BARCODE_RESOLVED or PICKER
 * @param gapAmount  PICKER only: rupees between the lowest and highest MRP above which the till
 *                   asks; null takes the configured default (pricing.picker_gap_amount)
 * @param gapPercent PICKER only: the same as a percentage of the lowest MRP (pricing.picker_gap_percent)
 */
public record SetMrpPolicy(UUID skuId, String policy, BigDecimal gapAmount, BigDecimal gapPercent) {}
