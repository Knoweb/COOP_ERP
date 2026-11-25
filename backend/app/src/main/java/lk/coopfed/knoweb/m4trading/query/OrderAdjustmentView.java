package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An adjustment recorded on an order (doc 24 §3.1; PRC-10).
 */
public record OrderAdjustmentView(
        UUID adjustmentId,
        UUID orderId,
        UUID ownerEntityId,
        UUID counterpartyEntityId,
        String adjustmentType,
        String kind,
        BigDecimal amount,
        String description,
        UUID recordedBy,
        Instant recordedAt,
        UUID invoiceDocumentId) {}
