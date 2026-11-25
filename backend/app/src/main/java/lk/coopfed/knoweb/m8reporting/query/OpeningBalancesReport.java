package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * Report containing opening balances across counterparties.
 */
public record OpeningBalancesReport(List<OpeningBalanceItemView> items, BigDecimal totalAmount) {}
