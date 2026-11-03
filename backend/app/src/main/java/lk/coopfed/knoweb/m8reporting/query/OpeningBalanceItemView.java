package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An item in the inter-entity opening balances list.
 */
public record OpeningBalanceItemView(
        UUID openingReceivableId,
        String docNumberDisplay,
        UUID counterpartyId,
        String counterpartyCode,
        String counterpartyName,
        String role,
        BigDecimal amount,
        String referenceNo,
        String notes,
        Instant signedAt,
        Instant createdAt) {}
