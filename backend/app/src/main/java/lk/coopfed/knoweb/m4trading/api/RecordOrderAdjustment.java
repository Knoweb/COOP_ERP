package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * RecordOrderAdjustment (PRC-10; FR-PRC-010; doc 24 §3.1, §6; doc 23 §3.2).
 * The seller records a manual adjustment (SPECIAL_DISCOUNT, DELIVERY_FEE) on an accepted order.
 */
public record RecordOrderAdjustment(UUID orderId, String adjustmentType, BigDecimal amount, String description) {}
