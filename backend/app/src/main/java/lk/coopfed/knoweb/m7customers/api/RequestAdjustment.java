package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PostAdjustment (27A section 6): a correction of the account's balance, asked for by one person
 * and posted only when another approves it. Above zero adds to what the customer owes.
 */
public record RequestAdjustment(UUID accountId, BigDecimal amount, String reason) {}
