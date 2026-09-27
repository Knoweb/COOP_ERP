package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;

/**
 * One journal line by account role (doc 24 section 3.9): the posting map's row applied to a
 * document's amount. Account roles, never account numbers: the chart of accounts is mapped when
 * the accounting system is chosen (J-02).
 */
public record Posting(
        String lineKind, String side, String debitRole, String creditRole, String amountSource, BigDecimal amount) {}
