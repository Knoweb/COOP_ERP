package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The view model of an inter-entity receivable opening balance (CAT-12). */
public record OpeningReceivableView(
        UUID openingReceivableId,
        UUID documentId,
        String docNumberDisplay,
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal amount,
        String referenceNo,
        UUID sellerSignedBy,
        Instant sellerSignedAt,
        UUID buyerSignedBy,
        Instant buyerSignedAt,
        String notes,
        Instant createdAt) {}
