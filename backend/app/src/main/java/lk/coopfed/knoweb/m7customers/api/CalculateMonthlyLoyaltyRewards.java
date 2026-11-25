package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * Calculates monthly qualifying spend, loyalty points reward and insurance eligibility (PGL-M3-03).
 * If {@code customerId} is specified, evaluates that customer; otherwise evaluates all active loyalty customers.
 */
public record CalculateMonthlyLoyaltyRewards(String periodMonth, UUID customerId) {}
