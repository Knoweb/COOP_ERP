package lk.coopfed.knoweb.m2catalogue.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a duplicate suspect pair (CAT-01, 22A section 6.1).
 */
public record DuplicateSuspectView(
        UUID suspectId,
        UUID skuA,
        UUID skuB,
        String skuACode,
        String skuAName,
        String skuBCode,
        String skuBName,
        BigDecimal score,
        List<String> reasons,
        String status,
        String decidedBy,
        Instant decidedAt,
        Instant createdAt) {}
